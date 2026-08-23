package com.getjobs.cloud.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.quota.QuotaModels.QuotaMeView;
import com.getjobs.cloud.quota.QuotaModels.QuotaReservation;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 额度领域服务集成测试：真实 PostgreSQL + RLS + Flyway V11，
 * 覆盖默认初始化 20/10、重复初始化、reserve/commit/release/consume、
 * 额度不足、幂等 replay、流水写入与 A/B 用户隔离。
 */
@Testcontainers
class QuotaServiceIntegrationTest {
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
    private static QuotaService service;

    @BeforeAll
    static void startQuotaService() throws Exception {
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
        service = new QuotaService(
                new QuotaRepository(jdbc, new ObjectMapper()),
                new QuotaProperties(),
                tenants,
                new DataSourceTransactionManager(dataSource),
                Clock.systemUTC()
        );
    }

    @AfterAll
    static void stopQuotaService() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- 初始化 ----

    @Test
    void initializeFreeCreatesDefault20And10ForCurrentUtcMonth() throws Exception {
        UUID user = createUser("quota-init@example.com");
        service.initializeFree(user);

        assertThat(quotaCount(user)).isEqualTo(2);
        assertThat(limitOf(user, "AI_ANALYSIS")).isEqualTo(20);
        assertThat(limitOf(user, "DELIVERY_CONFIRM")).isEqualTo(10);
        assertThat(planOf(user, "AI_ANALYSIS")).isEqualTo("FREE");
        Instant periodStart = currentMonthStart();
        assertThat(periodStartOf(user, "AI_ANALYSIS")).isEqualTo(periodStart);
        assertThat(periodEndOf(user, "AI_ANALYSIS")).isEqualTo(currentMonthEnd());
    }

    @Test
    void repeatedInitializeIsIdempotent() throws Exception {
        UUID user = createUser("quota-repeat@example.com");
        service.initializeFree(user);
        service.initializeFree(user);
        assertThat(quotaCount(user)).isEqualTo(2);
    }

    // ---- reserve / commit / release / consume ----

    @Test
    void reserveCommitAndReleaseMoveTheRowAndWriteLogs() throws Exception {
        UUID user = createUser("quota-cycle@example.com");
        service.initializeFree(user);
        UUID matchId = UUID.randomUUID();

        QuotaReservation reservation = service.reserve(
                user, "AI_ANALYSIS", "cycle:1", "JOB_MATCH", matchId, "AI 分析预占");
        assertThat(reservation.reservationId()).isNotNull();
        assertThat(reservedOf(user, "AI_ANALYSIS")).isEqualTo(1);
        assertThat(usedOf(user, "AI_ANALYSIS")).isZero();

        service.commitReservation(
                user, "AI_ANALYSIS", "cycle:1", "JOB_MATCH", matchId, "AI 分析成功结算");
        assertThat(usedOf(user, "AI_ANALYSIS")).isEqualTo(1);
        assertThat(reservedOf(user, "AI_ANALYSIS")).isZero();

        service.reserve(user, "AI_ANALYSIS", "cycle:2", "JOB_MATCH", matchId, "第二次预占");
        assertThat(reservedOf(user, "AI_ANALYSIS")).isEqualTo(1);
        service.releaseReservation(
                user, "AI_ANALYSIS", "cycle:2", "JOB_MATCH", matchId, "AI 分析最终失败返还");
        assertThat(reservedOf(user, "AI_ANALYSIS")).isZero();

        // 每次真实变更都写入一条清晰流水。
        assertThat(logCount(user)).isEqualTo(4);
        assertThat(logActions(user)).containsExactly(
                "RESERVE", "COMMIT", "RESERVE", "RELEASE");
        assertThat(logAmounts(user)).containsExactly(1L, 1L, 1L, 1L);
        assertThat(logBalances(user)).containsExactly(0L, 1L, 1L, 1L);
        assertThat(logReasons(user)).contains("AI 分析最终失败返还");
        assertThat(logReservationIds(user)).contains(reservation.reservationId());
    }

    @Test
    void consumeIncrementsUsedDirectlyAndWritesClearLog() throws Exception {
        UUID user = createUser("quota-consume@example.com");
        service.initializeFree(user);
        UUID taskId = UUID.randomUUID();

        service.consume(
                user, "DELIVERY_CONFIRM", "delivery:confirm:1", "DELIVERY_TASK", taskId,
                "用户确认投递，消耗一次投递额度");
        assertThat(usedOf(user, "DELIVERY_CONFIRM")).isEqualTo(1);
        assertThat(reservedOf(user, "DELIVERY_CONFIRM")).isZero();
        assertThat(logCount(user)).isEqualTo(1);
        assertThat(logActions(user)).containsExactly("COMMIT");
        assertThat(logReasons(user)).contains("用户确认投递，消耗一次投递额度");
    }

    // ---- 额度不足 ----

    @Test
    void insufficientQuotaThrowsQuotaExceededWithHttp429() throws Exception {
        UUID user = createUser("quota-exhaust@example.com");
        service.initializeFree(user);
        UUID refId = UUID.randomUUID();

        // AI 分析上限 20：第 21 次消耗必须 429 QUOTA_EXCEEDED。
        for (int i = 1; i <= 20; i++) {
            service.consume(user, "AI_ANALYSIS", "exhaust:consume:" + i, "JOB_MATCH", refId, "逐次消耗");
        }
        assertThatThrownBy(() -> service.consume(
                user, "AI_ANALYSIS", "exhaust:consume:21", "JOB_MATCH", refId, "超限消耗"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException api = (ApiException) exception;
                    assertThat(api.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.code()).isEqualTo("QUOTA_EXCEEDED");
                    assertThat(api.getMessage()).contains("AI 分析额度已用完");
                });
        // 失败操作不产生任何变更。
        assertThat(usedOf(user, "AI_ANALYSIS")).isEqualTo(20);
        assertThat(logCount(user)).isEqualTo(20);

        // 投递确认默认上限 10：第 11 次同样返回 429。
        for (int i = 1; i <= 10; i++) {
            service.consume(user, "DELIVERY_CONFIRM", "delivery:" + i,
                    "DELIVERY_TASK", refId, "逐次确认");
        }
        assertThatThrownBy(() -> service.consume(
                user, "DELIVERY_CONFIRM", "delivery:11", "DELIVERY_TASK", refId, "超限确认"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException api = (ApiException) exception;
                    assertThat(api.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.code()).isEqualTo("QUOTA_EXCEEDED");
                    assertThat(api.getMessage()).contains("投递确认额度已用完");
                });
    }

    // ---- 幂等 replay ----

    @Test
    void idempotentReplayNeverDoubleChargesOrAddsLogs() throws Exception {
        UUID user = createUser("quota-replay@example.com");
        service.initializeFree(user);
        UUID refId = UUID.randomUUID();

        service.reserve(user, "AI_ANALYSIS", "replay:analysis", "JOB_MATCH", refId, "预占");
        service.reserve(user, "AI_ANALYSIS", "replay:analysis", "JOB_MATCH", refId, "重复预占");
        assertThat(reservedOf(user, "AI_ANALYSIS")).isEqualTo(1);
        assertThat(logCount(user)).isEqualTo(1);

        service.commitReservation(user, "AI_ANALYSIS", "replay:analysis", "JOB_MATCH", refId, "结算");
        service.commitReservation(user, "AI_ANALYSIS", "replay:analysis", "JOB_MATCH", refId, "重复结算");
        assertThat(usedOf(user, "AI_ANALYSIS")).isEqualTo(1);
        assertThat(reservedOf(user, "AI_ANALYSIS")).isZero();
        assertThat(logCount(user)).isEqualTo(2);

        service.consume(user, "DELIVERY_CONFIRM", "replay:consume", "DELIVERY_TASK", refId, "投递确认");
        service.consume(user, "DELIVERY_CONFIRM", "replay:consume", "DELIVERY_TASK", refId, "重复投递确认");
        assertThat(usedOf(user, "DELIVERY_CONFIRM")).isEqualTo(1);
        assertThat(logCount(user)).isEqualTo(3);

        service.reserve(user, "AI_ANALYSIS", "replay:release", "JOB_MATCH", refId, "第二次预占");
        service.releaseReservation(user, "AI_ANALYSIS", "replay:release", "JOB_MATCH", refId, "返还");
        service.releaseReservation(user, "AI_ANALYSIS", "replay:release", "JOB_MATCH", refId, "重复返还");
        assertThat(logCount(user)).isEqualTo(5);
    }

    // ---- 流水 append-only ----

    @Test
    void usageLogsAreAppendOnlyAndHiddenFromOtherUsers() throws Exception {
        UUID user = createUser("quota-append@example.com");
        service.initializeFree(user);
        service.consume(user, "AI_ANALYSIS", "append:consume", "JOB_MATCH", UUID.randomUUID(), "消耗");

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + user + "', true)");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.quota_usage_logs WHERE user_id='" + user + "'"
                ))).isEqualTo(1);
                assertThatThrownBy(() -> stmt.execute(
                        "UPDATE app.quota_usage_logs SET amount=99 WHERE user_id='" + user + "'"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + user + "', true)");
                assertThatThrownBy(() -> stmt.execute(
                        "DELETE FROM app.quota_usage_logs WHERE user_id='" + user + "'"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();
        }
    }

    // ---- A/B 隔离与视图 ----

    @Test
    void quotaRowsAreIsolatedBetweenUsersAndViewOnlyReturnsOwnData() throws Exception {
        UUID userA = createUser("quota-a@example.com");
        UUID userB = createUser("quota-b@example.com");
        service.initializeFree(userA);
        service.initializeFree(userB);
        service.consume(userA, "AI_ANALYSIS", "ab:a:consume", "JOB_MATCH", UUID.randomUUID(), "A 消耗");

        assertThat(usedOf(userB, "AI_ANALYSIS")).isZero();
        assertThat(reservedOf(userB, "AI_ANALYSIS")).isZero();

        QuotaMeView view = service.currentView(userA);
        assertThat(view.plan()).isEqualTo("FREE");
        assertThat(view.resetCycle()).isEqualTo("MONTHLY");
        assertThat(view.resetAt()).isEqualTo(currentMonthEnd());
        assertThat(view.resources()).hasSize(2);
        assertThat(view.resources().get(0).resourceCode()).isEqualTo("AI_ANALYSIS");
        assertThat(view.resources().get(0).total()).isEqualTo(20);
        assertThat(view.resources().get(0).used()).isEqualTo(1);
        assertThat(view.resources().get(0).reserved()).isZero();
        assertThat(view.resources().get(0).remaining()).isEqualTo(19);
        assertThat(view.resources().get(1).resourceCode()).isEqualTo("DELIVERY_CONFIRM");
        assertThat(view.resources().get(1).total()).isEqualTo(10);
        assertThat(view.resources().get(1).remaining()).isEqualTo(10);

        // B 的视图里没有任何 A 的消费痕迹。
        QuotaMeView viewB = service.currentView(userB);
        assertThat(viewB.resources().get(0).used()).isZero();
        assertThat(viewB.resources().get(1).used()).isZero();
    }

    // ---- helpers ----

    private static UUID createUser(String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES ('" + id + "', '" + email + "', '$argon2id$test')");
        }
        return id;
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long quotaCount(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.user_quotas WHERE user_id='" + user + "'"
            ));
        }
    }

    private static long limitOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT limit_amount FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static String planOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleString(stmt.executeQuery(
                    "SELECT plan_code FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static Instant periodStartOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleInstant(stmt.executeQuery(
                    "SELECT period_start FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
            ));
        }
    }

    private static Instant periodEndOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleInstant(stmt.executeQuery(
                    "SELECT period_end FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
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

    private static long reservedOf(UUID user, String resource) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return singleLong(stmt.executeQuery(
                    "SELECT reserved_amount FROM app.user_quotas WHERE user_id='" + user + "' AND resource_code='" + resource + "'"
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

    private static List<String> logActions(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return listStrings(stmt.executeQuery(
                    "SELECT action FROM app.quota_usage_logs WHERE user_id='" + user + "' ORDER BY id"
            ));
        }
    }

    private static List<Long> logAmounts(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return listLongs(stmt.executeQuery(
                    "SELECT amount FROM app.quota_usage_logs WHERE user_id='" + user + "' ORDER BY id"
            ));
        }
    }

    private static List<Long> logBalances(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return listLongs(stmt.executeQuery(
                    "SELECT balance_after FROM app.quota_usage_logs WHERE user_id='" + user + "' ORDER BY id"
            ));
        }
    }

    private static List<String> logReasons(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            return listStrings(stmt.executeQuery(
                    "SELECT reason FROM app.quota_usage_logs WHERE user_id='" + user + "' ORDER BY id"
            ));
        }
    }

    private static List<UUID> logReservationIds(UUID user) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
            try (var rs = stmt.executeQuery(
                    "SELECT reservation_id FROM app.quota_usage_logs WHERE user_id='" + user + "' " +
                            "AND reservation_id IS NOT NULL ORDER BY id"
            )) {
                while (rs.next()) {
                    result.add(rs.getObject(1, UUID.class));
                }
            }
            return result;
        }
    }

    private static Instant currentMonthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant currentMonthEnd() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static long singleLong(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static Instant singleInstant(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getTimestamp(1).toInstant();
        }
    }

    private static List<String> listStrings(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
            return result;
        }
    }

    private static List<Long> listLongs(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            java.util.ArrayList<Long> result = new java.util.ArrayList<>();
            while (resultSet.next()) {
                result.add(resultSet.getLong(1));
            }
            return result;
        }
    }
}
