package com.getjobs.cloud.infrastructure;

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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='app' " +
                            "AND table_name NOT IN ('flyway_schema_history')"
            ))).isZero();
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
}
