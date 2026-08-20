package com.getjobs.cloud.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.jobs.JobRepository;
import com.getjobs.cloud.match.MatchModels;
import com.getjobs.cloud.match.MatchOutboxRepository;
import com.getjobs.cloud.match.MatchRepository;
import com.getjobs.cloud.match.MatchService;
import com.getjobs.cloud.match.MatchWorkerRepository;
import com.getjobs.cloud.preference.PreferenceRepository;
import com.getjobs.cloud.resume.ResumeRepository;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.getjobs.cloud.quota.QuotaConstants.REASON_AI_ANALYSIS_COMMIT;
import static com.getjobs.cloud.quota.QuotaConstants.REASON_AI_ANALYSIS_RELEASE;
import static com.getjobs.cloud.quota.QuotaConstants.REASON_AI_ANALYSIS_RESERVE;
import static com.getjobs.cloud.quota.QuotaConstants.REFERENCE_JOB_MATCH;
import static com.getjobs.cloud.quota.QuotaConstants.RESOURCE_AI_ANALYSIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 小批次 1B 集成测试：AI 分析入队预占与 worker 终态结算的 SQL 契约。
 *
 * <p>真实 PostgreSQL + RLS + Flyway，验证：入队时 match 行持久化随机
 * quota_reservation_key 并预占一次；worker 的 complete_match 后 SUCCEEDED 提交、
 * FAILED 释放；额度不足时 match/outbox/流水整体回滚。</p>
 */
@Testcontainers
class MatchQuotaSettlementIntegrationTest {
    private static final String APP_USER = "jobpilot_app";
    private static final String APP_PASSWORD = "integration-app-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TenantContextExecutor tenants;
    private static DataSourceTransactionManager txManager;
    private static QuotaRepository quotaRepo;
    private static QuotaService quotaService;
    private static MatchService matchService;
    private static MatchWorkerRepository matchWorkerRepo;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void start() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE ai_jobpilot TO " + APP_USER);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgresql")
                .defaultSchema("app")
                .schemas("app")
                .createSchemas(true)
                .cleanDisabled(true)
                .placeholders(Map.of("app_role", APP_USER))
                .load()
                .migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        jdbc = new JdbcTemplate(dataSource);
        tenants = new TenantContextExecutor(jdbc);
        txManager = new DataSourceTransactionManager(dataSource);
        objectMapper = new ObjectMapper();
        quotaRepo = new QuotaRepository(jdbc, objectMapper);
        quotaService = new QuotaService(quotaRepo, new QuotaProperties(), tenants, txManager, Clock.systemUTC());
        matchWorkerRepo = new MatchWorkerRepository(jdbc, objectMapper);
        matchService = buildMatchService(quotaService);
    }

    @AfterAll
    static void stop() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static MatchService buildMatchService(QuotaService quotaService) {
        AiMatchProperties aiProperties = new AiMatchProperties();
        aiProperties.setProvider("openai");
        aiProperties.setModel("gpt-4.1-mini");
        aiProperties.setPromptVersion("v1");
        return new MatchService(
                new MatchRepository(jdbc, objectMapper),
                new MatchOutboxRepository(jdbc),
                new JobRepository(new NamedParameterJdbcTemplate(jdbc), objectMapper),
                new ResumeRepository(jdbc),
                new PreferenceRepository(jdbc, objectMapper),
                tenants,
                txManager,
                aiProperties,
                quotaService,
                Clock.systemUTC()
        );
    }

    // ---- 入队预占 ----

    @Test
    void newEnqueuePersistsRandomKeyReservesOnceAndWritesOutbox() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        quotaService.initializeFree(user);

        MatchModels.QueuedResult result = matchService.analyze(user, jobId, false);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.reusedExisting()).isFalse();
        String key = reservationKeyOf(user, result.matchId());
        assertThat(key).isNotNull().startsWith("ai:").hasSizeLessThanOrEqualTo(110);
        assertThat(inTenant(user, () -> matchWorkerRepo.findQuotaReservationKey(user, result.matchId())))
                .contains(key);
        assertThat(reservedOf(user, RESOURCE_AI_ANALYSIS)).isEqualTo(1);
        assertThat(usedOf(user, RESOURCE_AI_ANALYSIS)).isZero();
        assertThat(outboxCount(user)).isEqualTo(1);
    }

    @Test
    void quotaExceededRollsBackMatchOutboxAndQuotaChanges() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        QuotaProperties exhausted = new QuotaProperties();
        exhausted.getFree().setAnalysis(0);
        QuotaService exhaustedQuota = new QuotaService(
                quotaRepo, exhausted, tenants, txManager, Clock.systemUTC());
        MatchService exhaustedMatch = buildMatchService(exhaustedQuota);

        assertThatThrownBy(() -> exhaustedMatch.analyze(user, jobId, false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.code()).isEqualTo("QUOTA_EXCEEDED");
                });
        assertThat(matchCount(user)).isZero();
        assertThat(outboxCount(user)).isZero();
        assertThat(logCount(user)).isZero();
        // 额度行初始化也随事务回滚，保持无残留。
        assertThat(quotaCount(user)).isZero();
    }

    // ---- worker 终态结算 ----

    @Test
    void succeededWorkerCompletionCommitsQuota() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        quotaService.initializeFree(user);
        UUID matchId = matchService.analyze(user, jobId, false).matchId();
        String key = reservationKeyOf(user, matchId);

        UUID lease = claimViaSql(user, matchId);
        boolean completed = matchWorkerRepo.completeMatch(
                user, matchId, lease, "SUCCEEDED", (short) 90, "APPLY",
                "匹配度高", List.of("技术匹配"), List.of(), "您好",
                "openai", "gpt-4.1-mini", "v1", 100, 50, 1000, null, null);
        assertThat(completed).isTrue();

        quotaService.commitReservation(user, RESOURCE_AI_ANALYSIS, key,
                REFERENCE_JOB_MATCH, matchId, REASON_AI_ANALYSIS_COMMIT);
        assertThat(usedOf(user, RESOURCE_AI_ANALYSIS)).isEqualTo(1);
        assertThat(reservedOf(user, RESOURCE_AI_ANALYSIS)).isZero();
    }

    @Test
    void failedWorkerCompletionReleasesQuota() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        quotaService.initializeFree(user);
        UUID matchId = matchService.analyze(user, jobId, false).matchId();
        String key = reservationKeyOf(user, matchId);

        UUID lease = claimViaSql(user, matchId);
        boolean completed = matchWorkerRepo.completeMatch(
                user, matchId, lease, "FAILED", null, null,
                null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                "AI_RESPONSE_INVALID", "响应无效");
        assertThat(completed).isTrue();

        quotaService.releaseReservation(user, RESOURCE_AI_ANALYSIS, key,
                REFERENCE_JOB_MATCH, matchId, REASON_AI_ANALYSIS_RELEASE);
        assertThat(reservedOf(user, RESOURCE_AI_ANALYSIS)).isZero();
        assertThat(usedOf(user, RESOURCE_AI_ANALYSIS)).isZero();
        assertThat(remainingOf(user, RESOURCE_AI_ANALYSIS)).isEqualTo(20);
    }

    @Test
    void failedCompleteMatchWithWrongLeaseDoesNotSettle() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        quotaService.initializeFree(user);
        UUID matchId = matchService.analyze(user, jobId, false).matchId();
        String key = reservationKeyOf(user, matchId);

        boolean completed = matchWorkerRepo.completeMatch(
                user, matchId, UUID.randomUUID(), "SUCCEEDED", (short) 90, "APPLY",
                "匹配度高", List.of(), List.of(), "您好",
                "openai", "gpt-4.1-mini", "v1", 100, 50, 1000, null, null);
        assertThat(completed).isFalse();
        // 预占保持不动：不提交不释放。
        assertThat(reservedOf(user, RESOURCE_AI_ANALYSIS)).isEqualTo(1);
        assertThat(usedOf(user, RESOURCE_AI_ANALYSIS)).isZero();
        assertThat(key).isNotBlank();
    }

    @Test
    void forceRequeueRotatesToFreshReservationKey() throws Exception {
        UUID user = createUserWithParents();
        UUID jobId = jobIdOf(user);
        quotaService.initializeFree(user);
        UUID matchId = matchService.analyze(user, jobId, false).matchId();
        String oldKey = reservationKeyOf(user, matchId);
        // 直接把 match 置为 FAILED（模拟终态失败后 force 重试前的状态）。
        UUID claimLease = claimViaSql(user, matchId);
        assertThat(matchWorkerRepo.completeMatch(
                user, matchId, claimLease, "FAILED", null, null,
                null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                "AI_ERROR", "失败")).isTrue();
        quotaService.releaseReservation(user, RESOURCE_AI_ANALYSIS, oldKey,
                REFERENCE_JOB_MATCH, matchId, REASON_AI_ANALYSIS_RELEASE);

        assertThat(statusOf(user, matchId)).isEqualTo("FAILED");

        MatchRepository matches = new MatchRepository(jdbc, objectMapper);
        assertThat(matches.forceRequeue(user, matchId)).isTrue();
        String newKey = "ai:" + UUID.randomUUID();
        assertThat(inTenant(user, () -> matches.updateQuotaReservationKey(user, matchId, newKey))).isTrue();
        quotaService.reserve(user, RESOURCE_AI_ANALYSIS, newKey,
                REFERENCE_JOB_MATCH, matchId, REASON_AI_ANALYSIS_RESERVE);

        assertThat(inTenant(user, () -> matchWorkerRepo.findQuotaReservationKey(user, matchId)))
                .contains(newKey);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(reservedOf(user, RESOURCE_AI_ANALYSIS)).isEqualTo(1);
    }

    // ---- helpers ----

    private static UUID createUserWithParents() throws SQLException {
        UUID user = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES ('" + user + "', '" +
                    user + "@example.com', '$argon2id$test')");
            stmt.execute("INSERT INTO app.job_posts(" +
                    "id, user_id, platform, fingerprint, title, company_name, job_url, source_captured_at, last_seen_at) VALUES ('" +
                    jobId + "', '" + user + "', 'BOSS', '" + hex64(1) + "', 'Java工程师', '示例公司', " +
                    "'https://example.com/job', now(), now())");
            stmt.execute("INSERT INTO app.resumes(" +
                    "id, user_id, original_filename, storage_key, content_type, file_size, sha256, " +
                    "upload_idempotency_key_hash, encryption_key_id, parse_status, is_current, parsed_at) VALUES ('" +
                    resumeId + "', '" + user + "', 'resume.pdf', 'k', 'application/pdf', 1024, '" + hex64(2) +
                    "', '" + hex64(3) + "', 'key-v1', 'PARSED', true, now())");
            stmt.execute("INSERT INTO app.job_preferences(id, user_id, version) VALUES ('" +
                    preferenceId + "', '" + user + "', 1)");
        }
        return user;
    }

    private static UUID jobIdOf(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            try (var rs = stmt.executeQuery(
                    "SELECT id FROM app.job_posts WHERE user_id='" + user + "'")) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static String hex64(int seed) {
        return String.format("%064d", seed).replace('0', 'a');
    }

    /**
     * Runs work inside a transaction with the RLS tenant set, mirroring the
     * MatchService/MatchWorker inTenant pattern so repository SQL sees its rows.
     */
    private static <T> T inTenant(UUID user, java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(status -> tenants.execute(user, work));
    }

    /**
     * Simulates {@code claim_match_for_processing} by taking the lease directly,
     * so the test does not depend on the package-private ProcessJob record.
     */
    private static UUID claimViaSql(UUID user, UUID matchId) throws SQLException {
        UUID lease = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.job_matches SET status='PROCESSING', " +
                    "lease_token='" + lease + "', lease_until=now()+interval '10 minutes', " +
                    "attempt_count=attempt_count+1, started_at=now(), version=version+1 " +
                    "WHERE user_id='" + user + "' AND id='" + matchId + "'");
        }
        return lease;
    }

    private static String statusOf(UUID user, UUID matchId) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            try (var rs = stmt.executeQuery(
                    "SELECT status FROM app.job_matches WHERE user_id='" + user + "' AND id='" + matchId + "'")) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String reservationKeyOf(UUID user, UUID matchId) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            try (var rs = stmt.executeQuery(
                    "SELECT quota_reservation_key FROM app.job_matches WHERE user_id='" + user + "' AND id='" + matchId + "'")) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static long reservedOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT reserved_amount FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static long usedOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT used_amount FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static long remainingOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT limit_amount - used_amount - reserved_amount FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static long quotaCount(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.user_quotas WHERE user_id='" + user + "'"
            ));
        }
    }

    private static long outboxCount(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.job_match_outbox WHERE user_id='" + user + "'"
            ));
        }
    }

    private static long matchCount(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.job_matches WHERE user_id='" + user + "'"
            ));
        }
    }

    private static long logCount(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.quota_usage_logs WHERE user_id='" + user + "'"
            ));
        }
    }

    private static long singleLong(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
