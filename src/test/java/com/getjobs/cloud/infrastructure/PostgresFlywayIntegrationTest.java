package com.getjobs.cloud.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresFlywayIntegrationTest {
    private static final String APP_USER = "jobpilot_app";
    private static final String APP_PASSWORD = "integration-app-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    @BeforeAll
    static void createApplicationRole() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE ai_jobpilot TO " + APP_USER);
        }
    }

    @Test
    @Order(1)
    void migratesEmptyPostgresBaselineWithoutLegacyTables() throws Exception {
        Flyway flyway = configuredFlyway();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='app' " +
                            "AND table_name NOT IN ('flyway_schema_history')"
            ))).isEqualTo(3);
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM pg_extension WHERE extname IN ('citext', 'pgcrypto')"
            ))).isEqualTo(2);
        }
    }

    @Test
    @Order(2)
    void applicationRoleCanUseFutureTablesButCannotCreateSchemaObjects() throws Exception {
        configuredFlyway().migrate();
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("CREATE TABLE app.infrastructure_permission_probe (id bigint primary key)");
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
             var statement = app.createStatement()) {
            statement.execute("INSERT INTO app.infrastructure_permission_probe(id) VALUES (1)");
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.infrastructure_permission_probe"
            ))).isEqualTo(1);
            assertThatThrownBy(() -> statement.execute("CREATE TABLE app.forbidden_ddl(id bigint)"))
                    .isInstanceOf(SQLException.class);
        } finally {
            try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS app.infrastructure_permission_probe");
            }
        }
    }

    @Test
    @Order(3)
    void applicationRoleIsIsolatedByProfileRlsAndCannotWriteAuditTableDirectly() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'b@example.com', '$argon2id$test')");
            statement.execute("INSERT INTO app.user_profiles(user_id, display_name) VALUES " +
                    "('" + userA + "', 'A'), ('" + userB + "', 'B')");
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.user_profiles"))).isEqualTo(1);
                assertThat(singleString(statement.executeQuery(
                        "SELECT display_name FROM app.user_profiles"
                ))).isEqualTo("A");
                assertThat(statement.executeUpdate(
                        "UPDATE app.user_profiles SET display_name='forbidden' WHERE user_id='" + userB + "'"
                )).isZero();
            }
            app.rollback();

            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.user_profiles"))).isZero();
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO app.audit_logs(actor_type, action, result) VALUES ('SYSTEM', 'DIRECT', 'SUCCESS')"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();

            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                assertThat(singleLong(statement.executeQuery(
                        "SELECT app.append_audit_log(NULL, 'SYSTEM', NULL, 'AUTH_LOGIN_FAILED', NULL, NULL, " +
                                "'SUCCESS', 'req-test', repeat('a', 64)::char(64), 'test', '{}'::jsonb)"
                ))).isPositive();
            }
            app.rollback();
        }
    }

    @Test
    @Order(4)
    void concurrentTenantsAndHikariConnectionReuseDoNotLeakRlsContext() throws Exception {
        configuredFlyway().migrate();
        UUID userC = UUID.randomUUID();
        UUID userD = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userC + "', 'c@example.com', '$argon2id$test'), " +
                    "('" + userD + "', 'd@example.com', '$argon2id$test')");
            statement.execute("INSERT INTO app.user_profiles(user_id, display_name) VALUES " +
                    "('" + userC + "', 'C'), ('" + userD + "', 'D')");
        }

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        poolConfig.setUsername(APP_USER);
        poolConfig.setPassword(APP_PASSWORD);
        poolConfig.setMaximumPoolSize(2);
        poolConfig.setMinimumIdle(2);
        poolConfig.setAutoCommit(false);

        try (HikariDataSource dataSource = new HikariDataSource(poolConfig);
             var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch queryTogether = new CountDownLatch(1);
            var userCFuture = executor.submit(() -> profileSeenBy(dataSource, userC, ready, queryTogether));
            var userDFuture = executor.submit(() -> profileSeenBy(dataSource, userD, ready, queryTogether));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            queryTogether.countDown();
            assertThat(userCFuture.get(5, TimeUnit.SECONDS)).isEqualTo("C");
            assertThat(userDFuture.get(5, TimeUnit.SECONDS)).isEqualTo("D");

            try (Connection reused = dataSource.getConnection(); var statement = reused.createStatement()) {
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.user_profiles"
                ))).isZero();
                reused.rollback();
            }
        }
    }

    private static String profileSeenBy(
            HikariDataSource dataSource,
            UUID userId,
            CountDownLatch ready,
            CountDownLatch queryTogether
    ) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.current_user_id', '" + userId + "', true)");
            ready.countDown();
            assertThat(queryTogether.await(5, TimeUnit.SECONDS)).isTrue();
            String result = singleString(statement.executeQuery(
                    "SELECT display_name FROM app.user_profiles"
            ));
            connection.rollback();
            return result;
        }
    }

    private static Flyway configuredFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgresql")
                .defaultSchema("app")
                .schemas("app")
                .createSchemas(true)
                .cleanDisabled(true)
                .placeholders(Map.of("app_role", APP_USER))
                .load();
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
