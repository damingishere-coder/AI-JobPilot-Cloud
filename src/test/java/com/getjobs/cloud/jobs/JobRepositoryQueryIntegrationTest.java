package com.getjobs.cloud.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level integration tests for the fixed job list/count SQL against
 * real PostgreSQL with RLS. After the CodeQL rework the SQL text is static and
 * every filter travels as a bind parameter, so these tests pin the behaviour
 * that must not change: filter semantics, pagination, the sort branches and
 * the "latest match per job only" rule.
 */
@Testcontainers
class JobRepositoryQueryIntegrationTest {
    private static final String APP_USER = "jobpilot_app";
    private static final String APP_PASSWORD = "integration-app-password";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    private static HikariDataSource dataSource;
    private static JobRepository jobs;
    private static TransactionTemplate transactions;
    private static TenantContextExecutor tenants;
    private static final Map<UUID, Integer> PREFERENCE_VERSIONS = new ConcurrentHashMap<>();

    @BeforeAll
    static void startRepository() throws Exception {
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
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

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jobs = new JobRepository(new NamedParameterJdbcTemplate(jdbc), new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tenants = new TenantContextExecutor(jdbc);
    }

    @AfterAll
    static void stopRepository() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- pagination and default sort ----

    @Test
    void paginationRespectsSizeOffsetAndDefaultsToLastSeenDescending() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        seedUser(user, "jobs-page@example.com");
        seedUser(other, "jobs-page-other@example.com");
        for (int i = 1; i <= 5; i++) {
            seedJob(user, "PAGE " + i, "BOSS", "分页岗位" + i, "A 公司", "杭州", "ACTIVE", null,
                    BASE, BASE.plus(i, ChronoUnit.HOURS), BASE);
        }
        // Another tenant's jobs must never leak through RLS.
        seedJob(other, "OTHER 1", "BOSS", "他人岗位", "B 公司", "上海", "ACTIVE", null,
                BASE, BASE.plus(100, ChronoUnit.HOURS), BASE);
        seedJob(other, "OTHER 2", "BOSS", "他人岗位", "B 公司", "上海", "ACTIVE", null,
                BASE, BASE.plus(101, ChronoUnit.HOURS), BASE);

        JobModels.Query query = query(1, 2, null, null, null, null, null, null,
                null, null, null);
        assertThat(inTenant(user, () -> jobs.count(user, query))).isEqualTo(5);

        List<JobModels.JobSummary> page1 = inTenant(user, () -> jobs.list(user, query));
        List<JobModels.JobSummary> page2 = inTenant(user, () -> jobs.list(user, query(2, 2,
                null, null, null, null, null, null, null, null, null)));
        List<JobModels.JobSummary> page3 = inTenant(user, () -> jobs.list(user, query(3, 2,
                null, null, null, null, null, null, null, null, null)));

        assertThat(page1).extracting(JobModels.JobSummary::id)
                .containsExactly(ids(user, "PAGE 5"), ids(user, "PAGE 4"));
        assertThat(page2).extracting(JobModels.JobSummary::id)
                .containsExactly(ids(user, "PAGE 3"), ids(user, "PAGE 2"));
        assertThat(page3).extracting(JobModels.JobSummary::id)
                .containsExactly(ids(user, "PAGE 1"));
        assertThat(page1.get(0).lastSeenAt()).isEqualTo(BASE.plus(5, ChronoUnit.HOURS));
    }

    // ---- filter values ----

    @Test
    void platformStatusAndKeywordFiltersMatchBoundValues() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "jobs-filter@example.com");
        seedJob(user, "BOSS-ACTIVE", "BOSS", "Java 后端工程师", "阿里", "杭州", "ACTIVE", null, BASE, BASE, BASE);
        seedJob(user, "BOSS-EXPIRED", "BOSS", "Java 测试工程师", "腾讯", "深圳", "EXPIRED", null, BASE, BASE, BASE);
        seedJob(user, "ZHILIAN-ACTIVE", "ZHILIAN", "产品经理", "字节", "北京", "ACTIVE", null, BASE, BASE, BASE);
        seedJob(user, "LIEPIN-REMOVED", "LIEPIN", "架构师", "百度", "上海", "REMOVED", null, BASE, BASE, BASE);

        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, "BOSS", null, null,
                null, null, null, null, null, null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, "ACTIVE", null,
                null, null, null, null, null, null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, "Java",
                null, null, null, null, null, null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, "BOSS", "ACTIVE", "Java",
                null, null, null, null, null, null)))).isEqualTo(1);
        // Keyword matches company_name and location too.
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, "杭州",
                null, null, null, null, null, null)))).isEqualTo(1);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, "腾讯",
                null, null, null, null, null, null)))).isEqualTo(1);
        // Unfiltered baseline matches every seeded job.
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, null, null, null)))).isEqualTo(4);

        List<JobModels.JobSummary> result = inTenant(user, () -> jobs.list(user, query(1, 10,
                "BOSS", "ACTIVE", "Java", null, null, null, null, null, null)));
        assertThat(result).extracting(JobModels.JobSummary::id).containsExactly(ids(user, "BOSS-ACTIVE"));
    }

    @Test
    void capturedRangeFiltersRespectBounds() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "jobs-range@example.com");
        for (int i = 1; i <= 4; i++) {
            seedJob(user, "RANGE " + i, "BOSS", "范围岗位" + i, "A 公司", "杭州", "ACTIVE", null,
                    BASE.plus(i, ChronoUnit.DAYS), BASE, BASE);
        }

        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                BASE.plus(2, ChronoUnit.DAYS), BASE.plus(3, ChronoUnit.DAYS), null,
                null, null, null)))).isEqualTo(2);
        List<JobModels.JobSummary> result = inTenant(user, () -> jobs.list(user, query(1, 10,
                null, null, null, BASE.plus(3, ChronoUnit.DAYS), null, null, null, null, null)));
        assertThat(result).extracting(JobModels.JobSummary::id)
                .containsExactlyInAnyOrder(ids(user, "RANGE 3"), ids(user, "RANGE 4"));
    }

    // ---- latest match semantics ----

    @Test
    void matchFiltersConsiderOnlyTheLatestMatchPerJob() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "jobs-match@example.com");
        seedJob(user, "MATCH J1", "BOSS", "匹配岗位一", "A 公司", "杭州", "ACTIVE", null, BASE, BASE, BASE);
        seedJob(user, "MATCH J2", "BOSS", "匹配岗位二", "A 公司", "杭州", "ACTIVE", null, BASE, BASE, BASE);
        seedJob(user, "MATCH J3", "BOSS", "无匹配岗位", "A 公司", "杭州", "ACTIVE", null, BASE, BASE, BASE);

        // J1: an old APPLY is shadowed by a newer SKIP; J2: a plain APPLY.
        seedMatch(user, ids(user, "MATCH J1"), "SUCCEEDED", "APPLY", 90, BASE.minus(2, ChronoUnit.HOURS));
        seedMatch(user, ids(user, "MATCH J1"), "SUCCEEDED", "SKIP", 50, BASE.minus(1, ChronoUnit.HOURS));
        seedMatch(user, ids(user, "MATCH J2"), "SUCCEEDED", "APPLY", 95, BASE.minus(1, ChronoUnit.HOURS));

        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, null, null, null)))).isEqualTo(3);
        // Only the latest match (SKIP) counts for J1.
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, "APPLY", null, null)))).isEqualTo(1);
        assertThat(inTenant(user, () -> jobs.list(user, query(1, 10, null, null, null,
                null, null, null, "APPLY", null, null))))
                .extracting(JobModels.JobSummary::id).containsExactly(ids(user, "MATCH J2"));
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, "SKIP", null, null)))).isEqualTo(1);
        // The latest score 50 fails minScore=80 and passes minScore=40.
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, null, null, 80)))).isEqualTo(1);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, null, null, 40)))).isEqualTo(2);
        // Match status filter and combined decision + score filter.
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, null, "SUCCEEDED", null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> jobs.count(user, query(1, 10, null, null, null,
                null, null, null, "SKIP", null, 40)))).isEqualTo(1);
    }

    // ---- sort enum ----

    @Test
    void sortEnumOrdersEverySupportedColumnWithNullsLast() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "jobs-sort@example.com");
        seedJob(user, "SORT A", "BOSS", "Alpha", "A 公司", "杭州", "ACTIVE", new BigDecimal("30"),
                BASE, BASE.plus(1, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS));
        seedJob(user, "SORT B", "BOSS", "Beta", "A 公司", "杭州", "ACTIVE", new BigDecimal("10"),
                BASE, BASE.plus(2, ChronoUnit.HOURS), BASE.plus(3, ChronoUnit.HOURS));
        seedJob(user, "SORT C", "BOSS", "Gamma", "A 公司", "杭州", "ACTIVE", new BigDecimal("20"),
                BASE, BASE.plus(3, ChronoUnit.HOURS), BASE.plus(2, ChronoUnit.HOURS));
        seedJob(user, "SORT D", "BOSS", "Zeta", "A 公司", "杭州", "ACTIVE", null,
                BASE, BASE.plus(4, ChronoUnit.HOURS), BASE.plus(1, ChronoUnit.HOURS));

        assertThat(sortedIds(user, JobModels.JobSort.TITLE_ASC)).containsExactly(
                ids(user, "SORT A"), ids(user, "SORT B"), ids(user, "SORT C"), ids(user, "SORT D"));
        assertThat(sortedIds(user, JobModels.JobSort.TITLE_DESC)).containsExactly(
                ids(user, "SORT D"), ids(user, "SORT C"), ids(user, "SORT B"), ids(user, "SORT A"));
        // NULL salary always lands last for both directions.
        assertThat(sortedIds(user, JobModels.JobSort.SALARY_MIN_ASC)).containsExactly(
                ids(user, "SORT B"), ids(user, "SORT C"), ids(user, "SORT A"), ids(user, "SORT D"));
        assertThat(sortedIds(user, JobModels.JobSort.SALARY_MIN_DESC)).containsExactly(
                ids(user, "SORT A"), ids(user, "SORT C"), ids(user, "SORT B"), ids(user, "SORT D"));
        assertThat(sortedIds(user, JobModels.JobSort.CREATED_ASC)).containsExactly(
                ids(user, "SORT D"), ids(user, "SORT C"), ids(user, "SORT B"), ids(user, "SORT A"));
        assertThat(sortedIds(user, JobModels.JobSort.CREATED_DESC)).containsExactly(
                ids(user, "SORT A"), ids(user, "SORT B"), ids(user, "SORT C"), ids(user, "SORT D"));
        assertThat(sortedIds(user, JobModels.JobSort.LAST_SEEN_ASC)).containsExactly(
                ids(user, "SORT A"), ids(user, "SORT B"), ids(user, "SORT C"), ids(user, "SORT D"));
        assertThat(sortedIds(user, JobModels.JobSort.LAST_SEEN_DESC)).containsExactly(
                ids(user, "SORT D"), ids(user, "SORT C"), ids(user, "SORT B"), ids(user, "SORT A"));
    }

    private static List<UUID> sortedIds(UUID user, JobModels.JobSort sort) {
        return inTenant(user, () -> jobs.list(user, query(1, 10, null, null, null,
                        null, null, sort, null, null, null)))
                .stream().map(JobModels.JobSummary::id).toList();
    }

    // ---- helpers ----

    private static <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private static JobModels.Query query(int page, int size, String platform, String status,
                                         String keyword, Instant from, Instant to, JobModels.JobSort sort,
                                         String decision, String matchStatus, Integer minScore) {
        return new JobModels.Query(page, size, platform, status, keyword, from, to, sort,
                decision, matchStatus, minScore);
    }

    /** Deterministic id derived from the seed tag so assertions can reference rows by tag. */
    private static UUID ids(UUID user, String tag) {
        return UUID.nameUUIDFromBytes((user + ":" + tag).getBytes());
    }

    private static String fingerprint(UUID id) {
        String hex = id.toString().replace("-", "");
        return hex + hex;
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedUser(UUID userId, String email) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "INSERT INTO app.users(id, email, password_hash) VALUES (?, ?, '$argon2id$test')")) {
            statement.setObject(1, userId);
            statement.setString(2, email);
            statement.executeUpdate();
        }
    }

    private static void seedJob(UUID user, String tag, String platform, String title, String company,
                                String location, String status, BigDecimal salaryMinK,
                                Instant capturedAt, Instant lastSeenAt, Instant createdAt) throws SQLException {
        UUID id = ids(user, tag);
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     INSERT INTO app.job_posts(
                         id, user_id, platform, fingerprint, title, company_name, salary_min_k,
                         location, status, job_url, source_captured_at, last_seen_at, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, user);
            statement.setString(3, platform);
            statement.setString(4, fingerprint(id));
            statement.setString(5, title);
            statement.setString(6, company);
            statement.setBigDecimal(7, salaryMinK);
            statement.setString(8, location);
            statement.setString(9, status);
            statement.setString(10, "https://www.zhipin.com/job_detail/" + id + ".html");
            statement.setObject(11, java.sql.Timestamp.from(capturedAt));
            statement.setObject(12, java.sql.Timestamp.from(lastSeenAt));
            statement.setObject(13, java.sql.Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private static void seedMatch(UUID user, UUID jobPostId, String status, String decision,
                                  int score, Instant createdAt) throws SQLException {
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        int preferenceVersion = PREFERENCE_VERSIONS.merge(user, 1, Integer::sum);
        try (Connection owner = ownerConnection()) {
            try (PreparedStatement resume = owner.prepareStatement("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, parse_status
                    ) VALUES (?, ?, 'resume.pdf', ?, 'application/pdf', 100, ?, ?, 'v1', 'PARSED')
                    """)) {
                resume.setObject(1, resumeId);
                resume.setObject(2, user);
                resume.setString(3, "objects/" + resumeId);
                resume.setString(4, fingerprint(resumeId));
                resume.setString(5, fingerprint(matchId));
                resume.executeUpdate();
            }
            try (PreparedStatement preference = owner.prepareStatement(
                    "INSERT INTO app.job_preferences(id, user_id, version, is_current) VALUES (?, ?, ?, ?)")) {
                preference.setObject(1, preferenceId);
                preference.setObject(2, user);
                preference.setInt(3, preferenceVersion);
                preference.setBoolean(4, preferenceVersion == 1);
                preference.executeUpdate();
            }
            try (PreparedStatement match = owner.prepareStatement("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status, decision, score,
                        input_fingerprint, created_at, completed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                match.setObject(1, matchId);
                match.setObject(2, user);
                match.setObject(3, jobPostId);
                match.setObject(4, resumeId);
                match.setObject(5, preferenceId);
                match.setString(6, status);
                match.setString(7, decision);
                match.setInt(8, score);
                match.setString(9, fingerprint(matchId));
                match.setObject(10, java.sql.Timestamp.from(createdAt));
                match.setObject(11, java.sql.Timestamp.from(createdAt));
                match.executeUpdate();
            }
        }
    }
}
