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
import java.util.concurrent.Future;
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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='app' " +
                            "AND table_name NOT IN ('flyway_schema_history')"
            ))).isEqualTo(12);
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

    @Test
    @Order(5)
    void roundFourTablesEnforceRlsAndWorkerClaimsOnlyThroughNarrowFunction() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'round4-a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'round4-b@example.com', '$argon2id$test')");
            statement.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true
                    )
                    """.formatted(resumeId, userA));
            statement.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb)
                    """.formatted(preferenceId, userA));
            statement.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(jobId, userA));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.resumes"))).isEqualTo(1);
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.job_preferences"))).isEqualTo(1);
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.job_posts"))).isEqualTo(1);
                assertThat(statement.executeUpdate(
                        "UPDATE app.job_posts SET title='越权修改' WHERE user_id='" + userB + "' AND id='" + jobId + "'"
                )).isZero();
            }
            app.rollback();

            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.current_user_id', '" + userB + "', true)");
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.resumes WHERE id='" + resumeId + "'"
                ))).isZero();
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.job_preferences WHERE id='" + preferenceId + "'"
                ))).isZero();
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.job_posts WHERE id='" + jobId + "'"
                ))).isZero();
            }
            app.rollback();

            try (var statement = app.createStatement()) {
                assertThat(singleString(statement.executeQuery(
                        "SELECT owner_user_id::text FROM app.claim_resume_parse_job(300)"
                ))).isEqualTo(userA.toString());
                assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.resumes"))).isZero();
            }
        }
    }

    @Test
    @Order(6)
    void roundFiveMatchTablesEnforceRlsCheckConstraintsAndWorkerFunctions() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'r5-a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'r5-b@example.com', '$argon2id$test')");
            statement.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, userA));
            statement.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                        review_threshold, priority_apply_threshold, apply_threshold)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb, 60, 65, 75)
                    """.formatted(preferenceId, userA));
            statement.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(jobId, userA));
            statement.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'PENDING', repeat('d', 64)
                    )
                    """.formatted(matchId, userA, jobId, resumeId, preferenceId));
        }

        // Verify RLS: user A can see their match, user B cannot
        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + matchId + "'"
                ))).isEqualTo(1);
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.job_match_outbox"
                ))).isZero();
            }
            app.rollback();

            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.current_user_id', '" + userB + "', true)");
                assertThat(singleLong(statement.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + matchId + "'"
                ))).isZero();
            }
            app.rollback();

            // Test threshold check constraint via owner
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                assertThatThrownBy(() -> stmt.execute(
                        "UPDATE app.job_preferences SET apply_threshold=50 WHERE id='" + preferenceId + "'"
                )).isInstanceOf(SQLException.class);
            }

            // Test worker functions via app role
            app.setAutoCommit(false);
            try (var statement = app.createStatement()) {
                // claim_match_for_processing should work via SECURITY DEFINER
                var rs = statement.executeQuery(
                        "SELECT * FROM app.claim_match_for_processing('" + userA + "', '" + matchId + "', 300, 3)"
                );
                assertThat(rs.next()).isTrue();
                UUID leaseToken = rs.getObject("lease_token", UUID.class);
                assertThat(leaseToken).isNotNull();
                rs.close();

                // complete_match should work
                var completeRs = statement.executeQuery(
                        "SELECT app.complete_match('" + userA + "', '" + matchId + "', '" + leaseToken + "', " +
                                "'SUCCEEDED', CAST(85 AS smallint), 'APPLY', '测试摘要', " +
                                "'[\"优势\"]'::jsonb, '[]'::jsonb, '你好', " +
                                "'openai', 'gpt-4', 'v1', 100, 200, 500, NULL, NULL)"
                );
                assertThat(completeRs.next()).isTrue();
                assertThat(completeRs.getBoolean(1)).isTrue();
                completeRs.close();
            }
            app.rollback();
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

    @Test
    @Order(7)
    void roundFiveMatchTablesEnforceCompositeFksUniqueFingerprintAndOutboxConstraints() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID resumeA = UUID.randomUUID();
        UUID prefA = UUID.randomUUID();
        UUID jobA = UUID.randomUUID();
        UUID matchA = UUID.randomUUID();
        UUID matchB = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'r5-ck-a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'r5-ck-b@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeA, userA));
            stmt.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                        review_threshold, priority_apply_threshold, apply_threshold)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb, 50, 60, 80)
                    """.formatted(prefA, userA));
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(jobA, userA));
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'PENDING', repeat('d', 64)
                    )
                    """.formatted(matchA, userA, jobA, resumeA, prefA));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            // Verify composite FK enforcement: match must reference job owned by same user
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + matchA + "'"
                ))).isEqualTo(1);
            }
            app.rollback();

            // Verify cross-user visibility: user B cannot see user A's match
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userB + "', true)");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + matchA + "'"
                ))).isZero();
            }
            app.rollback();

            // Verify unique fingerprint per user
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThatThrownBy(() -> stmt.execute(
                        "INSERT INTO app.job_matches(id, user_id, job_post_id, resume_id, " +
                                "preference_id, status, input_fingerprint) VALUES ('" + matchB +
                                "', '" + userA + "', '" + jobA + "', '" + resumeA + "', '" +
                                prefA + "', 'PENDING', repeat('d', 64))"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();

            // Verify outbox FK references match owned by same user
            UUID outboxId = UUID.randomUUID();
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                stmt.execute("""
                        INSERT INTO app.job_match_outbox(id, user_id, job_match_id, event_type, event_key)
                        VALUES ('%s', '%s', '%s', 'JOB_ANALYSIS_REQUESTED', 'match:test:unique-ck')
                        """.formatted(outboxId, userA, matchA));
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.job_match_outbox WHERE id='" + outboxId + "'"
                ))).isEqualTo(1);
            }
            app.rollback();

            // Verify outbox claim, lease semantics and confirm cycle
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                // First insert a PENDING outbox entry
                UUID outboxId2 = UUID.randomUUID();
                stmt.execute("""
                        INSERT INTO app.job_match_outbox(id, user_id, job_match_id, event_type, event_key, status)
                        VALUES ('%s', '%s', '%s', 'JOB_ANALYSIS_REQUESTED', 'match:test:claim-cycle', 'PENDING')
                        """.formatted(outboxId2, userA, matchA));

                // Claim via SECURITY DEFINER function; must stay PENDING and expose attempt number
                var claimed = stmt.executeQuery("SELECT * FROM app.claim_match_outbox_publish(300)");
                assertThat(claimed.next()).isTrue();
                assertThat(claimed.getObject("outbox_id", UUID.class)).isEqualTo(outboxId2);
                UUID outboxLease = claimed.getObject("lease_token", UUID.class);
                assertThat(outboxLease).isNotNull();
                assertThat(claimed.getInt("attempt_number")).isEqualTo(1);
                claimed.close();

                var stillPending = stmt.executeQuery(
                        "SELECT status FROM app.job_match_outbox WHERE id='" + outboxId2 + "'");
                assertThat(stillPending.next()).isTrue();
                assertThat(stillPending.getString(1)).isEqualTo("PENDING");
                stillPending.close();

                // Wrong lease cannot confirm
                var wrongConfirm = stmt.executeQuery(
                        "SELECT app.confirm_match_outbox_published('" + outboxId2 + "', '" + UUID.randomUUID() + "')");
                assertThat(wrongConfirm.next()).isTrue();
                assertThat(wrongConfirm.getBoolean(1)).isFalse();
                wrongConfirm.close();

                // Wrong lease cannot release
                var wrongRelease = stmt.executeQuery(
                        "SELECT app.release_match_outbox_lease('" + outboxId2 + "', '" + UUID.randomUUID() + "', 10)");
                assertThat(wrongRelease.next()).isTrue();
                assertThat(wrongRelease.getBoolean(1)).isFalse();
                wrongRelease.close();

                // Status and lease untouched by the failed attempts
                var untouched = stmt.executeQuery(
                        "SELECT status, lease_token FROM app.job_match_outbox WHERE id='" + outboxId2 + "'");
                assertThat(untouched.next()).isTrue();
                assertThat(untouched.getString(1)).isEqualTo("PENDING");
                assertThat(untouched.getObject(2, UUID.class)).isEqualTo(outboxLease);
                untouched.close();

                // Correct lease confirms PUBLISHED (simulates Redis XADD success)
                var confirmed = stmt.executeQuery(
                        "SELECT app.confirm_match_outbox_published('" + outboxId2 + "', '" + outboxLease + "')");
                assertThat(confirmed.next()).isTrue();
                assertThat(confirmed.getBoolean(1)).isTrue();
                confirmed.close();

                var published = stmt.executeQuery(
                        "SELECT status, lease_token FROM app.job_match_outbox WHERE id='" + outboxId2 + "'");
                assertThat(published.next()).isTrue();
                assertThat(published.getString(1)).isEqualTo("PUBLISHED");
                assertThat(published.getObject(2)).isNull();
                published.close();
            }
            app.rollback();

            // Verify claim match and retry with next_attempt_at
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                // Claim match
                var claimed = stmt.executeQuery(
                        "SELECT * FROM app.claim_match_for_processing('" + userA + "', '" + matchA + "', 300, 3)");
                assertThat(claimed.next()).isTrue();
                UUID matchLease = claimed.getObject("lease_token", UUID.class);
                assertThat(matchLease).isNotNull();
                claimed.close();

                // Retry later (simulates retryable error)
                var retried = stmt.executeQuery(
                        "SELECT app.retry_match_later('" + userA + "', '" + matchA + "', '" + matchLease + "', 10)");
                assertThat(retried.next()).isTrue();
                assertThat(retried.getBoolean(1)).isTrue();
                retried.close();

                // Verify next_attempt_at is set
                var ts = stmt.executeQuery(
                        "SELECT next_attempt_at FROM app.job_matches WHERE id='" + matchA + "'");
                assertThat(ts.next()).isTrue();
                assertThat(ts.getTimestamp(1)).isNotNull();
                ts.close();
            }
            app.rollback();

            // Verify FAILED force re-queue: atomically reset + write outbox
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                // First complete a match as FAILED
                UUID failMatch = UUID.randomUUID();
                stmt.execute("""
                        INSERT INTO app.job_matches(
                            id, user_id, job_post_id, resume_id, preference_id, status,
                            input_fingerprint
                        ) VALUES (
                            '%s', '%s', '%s', '%s', '%s', 'FAILED', repeat('e', 64)
                        )
                        """.formatted(failMatch, userA, jobA, resumeA, prefA));

                // Force re-queue
                var requeued = stmt.executeQuery(
                        "SELECT app.force_requeue_failed_match('" + userA + "', '" + failMatch + "')");
                assertThat(requeued.next()).isTrue();
                assertThat(requeued.getBoolean(1)).isTrue();
                requeued.close();

                // Verify match is now PENDING
                var statusRs = stmt.executeQuery(
                        "SELECT status FROM app.job_matches WHERE id='" + failMatch + "'");
                assertThat(statusRs.next()).isTrue();
                assertThat(statusRs.getString(1)).isEqualTo("PENDING");
                statusRs.close();

                // Verify still exactly one Match row — no duplicates created
                var matchCountRs = stmt.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + failMatch + "'");
                assertThat(matchCountRs.next()).isTrue();
                assertThat(matchCountRs.getLong(1)).isEqualTo(1);
                matchCountRs.close();

                // Verify exactly one REQUESTED outbox entry was created
                var outboxRs = stmt.executeQuery(
                        "SELECT count(*) FROM app.job_match_outbox WHERE job_match_id='" + failMatch + "'");
                assertThat(outboxRs.next()).isTrue();
                assertThat(outboxRs.getLong(1)).isEqualTo(1);
                outboxRs.close();

                // Event key must be collision-safe (not epoch-second based)
                var keyRs = stmt.executeQuery(
                        "SELECT event_key FROM app.job_match_outbox WHERE job_match_id='" + failMatch + "'");
                assertThat(keyRs.next()).isTrue();
                String eventKey = keyRs.getString(1);
                assertThat(eventKey).contains("force-requeue");
                keyRs.close();

                // Requeue again in the same transaction: put the match back to FAILED
                // (simulating a second failed attempt), then requeue — the second call
                // must also succeed with a distinct event key (no unique collision).
                stmt.execute("UPDATE app.job_matches SET status='FAILED' WHERE id='" + failMatch + "'");
                var requeuedAgain = stmt.executeQuery(
                        "SELECT app.force_requeue_failed_match('" + userA + "', '" + failMatch + "')");
                assertThat(requeuedAgain.next()).isTrue();
                assertThat(requeuedAgain.getBoolean(1)).isTrue();
                requeuedAgain.close();
                var keyRs2 = stmt.executeQuery(
                        "SELECT count(DISTINCT event_key) FROM app.job_match_outbox WHERE job_match_id='" + failMatch + "'");
                assertThat(keyRs2.next()).isTrue();
                assertThat(keyRs2.getLong(1)).isEqualTo(2);
                keyRs2.close();
            }
            app.rollback();

            // Verify FAILED force re-queue only works for FAILED, not for other statuses
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                // Try force re-queue on PENDING match — should return false
                var noRequeue = stmt.executeQuery(
                        "SELECT app.force_requeue_failed_match('" + userA + "', '" + matchA + "')");
                assertThat(noRequeue.next()).isTrue();
                assertThat(noRequeue.getBoolean(1)).isFalse();
                noRequeue.close();
            }
            app.rollback();
        }
    }

    private static String singleString(java.sql.ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    @Test
    @Order(8)
    void crossUserCompositeForeignKeysRejectFailedInsertsAndHideWithoutTenant() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID resumeA = UUID.randomUUID();
        UUID prefA = UUID.randomUUID();
        UUID jobA = UUID.randomUUID();
        UUID matchA = UUID.randomUUID();
        UUID resumeB = UUID.randomUUID();
        UUID prefB = UUID.randomUUID();
        UUID jobB = UUID.randomUUID();
        UUID matchB = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'r5-fk-a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'r5-fk-b@example.com', '$argon2id$test')");
            for (UUID resumeId : new UUID[]{resumeA, resumeB}) {
                UUID ownerId = resumeId.equals(resumeA) ? userA : userB;
                stmt.execute("""
                        INSERT INTO app.resumes(
                            id, user_id, original_filename, storage_key, content_type, file_size,
                            sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                        ) VALUES (
                            '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                            repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                        )
                        """.formatted(resumeId, ownerId));
            }
            for (UUID prefId : new UUID[]{prefA, prefB}) {
                UUID ownerId = prefId.equals(prefA) ? userA : userB;
                stmt.execute("""
                        INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                            review_threshold, priority_apply_threshold, apply_threshold)
                        VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb, 60, 65, 75)
                        """.formatted(prefId, ownerId));
            }
            for (UUID job : new UUID[]{jobA, jobB}) {
                UUID ownerId = job.equals(jobA) ? userA : userB;
                stmt.execute("""
                        INSERT INTO app.job_posts(
                            id, user_id, platform, fingerprint, title, company_name, job_url,
                            source_captured_at, last_seen_at
                        ) VALUES (
                            '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                            'https://www.zhipin.com/job_detail/test.html', now(), now()
                        )
                        """.formatted(job, ownerId));
            }
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'PENDING', repeat('d', 64)
                    )
                    """.formatted(matchA, userA, jobA, resumeA, prefA));
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'PENDING', repeat('f', 64)
                    )
                    """.formatted(matchB, userB, jobB, resumeB, prefB));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            // Without app.current_user_id the app role sees no Match/Outbox rows at all
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                assertThat(singleLong(stmt.executeQuery("SELECT count(*) FROM app.job_matches"))).isZero();
                assertThat(singleLong(stmt.executeQuery("SELECT count(*) FROM app.job_match_outbox"))).isZero();
            }
            app.rollback();

            // Each failing cross-user insert needs its own transaction: a constraint
            // violation aborts the transaction for any further statements.
            String[] failingInserts = {
                    // Match referencing user B's job
                    "INSERT INTO app.job_matches(id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint) " +
                            "VALUES ('" + UUID.randomUUID() + "', '" + userA + "', '" + jobB + "', '" + resumeA + "', '" + prefA + "', 'PENDING', repeat('e', 64))",
                    // Match referencing user B's resume
                    "INSERT INTO app.job_matches(id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint) " +
                            "VALUES ('" + UUID.randomUUID() + "', '" + userA + "', '" + jobA + "', '" + resumeB + "', '" + prefA + "', 'PENDING', repeat('e', 64))",
                    // Match referencing user B's preference
                    "INSERT INTO app.job_matches(id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint) " +
                            "VALUES ('" + UUID.randomUUID() + "', '" + userA + "', '" + jobA + "', '" + resumeA + "', '" + prefB + "', 'PENDING', repeat('e', 64))",
                    // Outbox referencing user B's match
                    "INSERT INTO app.job_match_outbox(id, user_id, job_match_id, event_type, event_key) " +
                            "VALUES ('" + UUID.randomUUID() + "', '" + userA + "', '" + matchB + "', 'JOB_ANALYSIS_REQUESTED', 'match:test:fk-cross')"
            };
            for (String insert : failingInserts) {
                app.setAutoCommit(false);
                try (var stmt = app.createStatement()) {
                    stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                    assertThatThrownBy(() -> stmt.execute(insert))
                            .as("cross-user reference must be rejected: %s", insert)
                            .isInstanceOf(SQLException.class);
                }
                app.rollback();
            }

            // User A's own data remains visible under its tenant context
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.job_matches WHERE id='" + matchA + "'"
                ))).isEqualTo(1);
            }
            app.rollback();
        }
    }

    @Test
    @Order(9)
    void claimFunctionsHonorMaxAttemptsAndExposeAttemptNumbers() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        int maxAttempts = 3;

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            // Clear matches left by earlier tests so fallback claims are deterministic
            stmt.execute("UPDATE app.job_matches SET status='SUCCEEDED', completed_at=now() " +
                    "WHERE status IN ('PENDING','PROCESSING')");
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r5-max@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, user));
            stmt.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                        review_threshold, priority_apply_threshold, apply_threshold)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb, 60, 65, 75)
                    """.formatted(preferenceId, user));
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(jobId, user));
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint, attempt_count, next_attempt_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'PENDING', repeat('d', 64),
                        %d, now()
                    )
                    """.formatted(matchId, user, jobId, resumeId, preferenceId, maxAttempts));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            // Specific claim: attempt_count = maxAttempts → 0 rows and count unchanged
            assertThat(singleLong(app.createStatement().executeQuery(
                    "SELECT count(*) FROM app.claim_match_for_processing('" + user + "', '" + matchId + "', 300, " + maxAttempts + ")"
            ))).isZero();
            assertThat(matchAttemptCount(matchId)).isEqualTo(maxAttempts);

            // Fallback claim: same behavior
            assertThat(singleLong(app.createStatement().executeQuery(
                    "SELECT count(*) FROM app.claim_one_pending_match(300, " + maxAttempts + ")"
            ))).isZero();
            assertThat(matchAttemptCount(matchId)).isEqualTo(maxAttempts);

            // attempt_count = maxAttempts - 1 → claimable as the final attempt
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                stmt.execute("UPDATE app.job_matches SET attempt_count=" + (maxAttempts - 1) +
                        ", next_attempt_at=now() WHERE id='" + matchId + "'");
            }

            try (var stmt = app.createStatement()) {
                var claimed = stmt.executeQuery(
                        "SELECT * FROM app.claim_match_for_processing('" + user + "', '" + matchId + "', 300, " + maxAttempts + ")");
                assertThat(claimed.next()).isTrue();
                assertThat(claimed.getInt("attempt_number")).isEqualTo(maxAttempts);
                claimed.close();
            }
            assertThat(matchAttemptCount(matchId)).isEqualTo(maxAttempts);

            // The match is PROCESSING with a lease now; make it claimable again with
            // attempt_count at the max so the limit itself blocks the claim.
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                stmt.execute("UPDATE app.job_matches SET status='PENDING', lease_token=NULL, " +
                        "lease_until=NULL, next_attempt_at=now() WHERE id='" + matchId + "'");
            }
            assertThat(singleLong(app.createStatement().executeQuery(
                    "SELECT count(*) FROM app.claim_match_for_processing('" + user + "', '" + matchId + "', 300, " + maxAttempts + ")"
            ))).isZero();
            assertThat(singleLong(app.createStatement().executeQuery(
                    "SELECT count(*) FROM app.claim_one_pending_match(300, " + maxAttempts + ")"
            ))).isZero();
            assertThat(matchAttemptCount(matchId)).isEqualTo(maxAttempts);

            // Fallback claim on the final allowed attempt returns attempt_number = maxAttempts
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                stmt.execute("UPDATE app.job_matches SET attempt_count=" + (maxAttempts - 1) +
                        ", next_attempt_at=now() WHERE id='" + matchId + "'");
            }
            try (var stmt = app.createStatement()) {
                var claimed = stmt.executeQuery(
                        "SELECT * FROM app.claim_one_pending_match(300, " + maxAttempts + ")");
                assertThat(claimed.next()).isTrue();
                assertThat(claimed.getObject("match_id", UUID.class)).isEqualTo(matchId);
                assertThat(claimed.getInt("attempt_number")).isEqualTo(maxAttempts);
                claimed.close();
            }
            assertThat(matchAttemptCount(matchId)).isEqualTo(maxAttempts);
        }
    }

    private static int matchAttemptCount(UUID matchId) throws SQLException {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var rs = stmt.executeQuery("SELECT attempt_count FROM app.job_matches WHERE id='" + matchId + "'");
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    // ---- Round 6: delivery tasks, plugin devices/tokens and plugin execution ----

    @Test
    @Order(10)
    void roundSixTablesEnforceRlsCheckConstraintsAndAppendOnlyEvents() throws Exception {
        configuredFlyway().migrate();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userA + "', 'r6-a@example.com', '$argon2id$test'), " +
                    "('" + userB + "', 'r6-b@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES (
                        '%s', '%s', '我的 Edge', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb
                    )
                    """.formatted(deviceA, userA));
            // CHECK: capabilities outside the allowlist are rejected
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.plugin_devices(id, user_id, device_name, installation_id_hash, " +
                            "extension_version, capabilities) VALUES ('" + UUID.randomUUID() + "', '" + userB +
                            "', 'Bad', repeat('b', 64), '1.0.0', '[\"LINKEDIN\"]'::jsonb)"
            )).isInstanceOf(SQLException.class);
            // CHECK: unknown task status is rejected
            assertThatThrownBy(() -> stmt.execute(
                    "UPDATE app.plugin_devices SET status='SUSPENDED' WHERE id='" + deviceA + "'"
            )).isInstanceOf(SQLException.class);
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            // RLS: user B cannot see user A's device, and without a tenant context nothing is visible
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userB + "', true)");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.plugin_devices WHERE id='" + deviceA + "'"
                ))).isZero();
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.delivery_tasks"
                ))).isZero();
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.delivery_task_events"
                ))).isZero();
            }
            app.rollback();

            // Events are append-only: UPDATE/DELETE are rejected even in the right tenant
            UUID taskId = UUID.randomUUID();
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                stmt.execute("""
                        INSERT INTO app.job_posts(
                            id, user_id, platform, fingerprint, title, company_name, job_url,
                            source_captured_at, last_seen_at
                        ) VALUES (
                            '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                            'https://www.zhipin.com/job_detail/test.html', now(), now()
                        )
                        """.formatted(UUID.randomUUID(), userA));
                stmt.execute("""
                        INSERT INTO app.delivery_tasks(
                            id, user_id, job_post_id, status, idempotency_key_hash, idempotency_payload_hash
                        ) SELECT '%s', '%s', id, 'PENDING_CONFIRMATION', repeat('d', 64), repeat('e', 64)
                        FROM app.job_posts WHERE user_id='%s' LIMIT 1
                        """.formatted(taskId, userA, userA));
            }
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                stmt.execute("""
                        INSERT INTO app.delivery_task_events(
                            user_id, delivery_task_id, event_type, actor_type, event_key
                        ) VALUES ('%s', '%s', 'CREATED', 'SYSTEM', 'test:created')
                        """.formatted(userA, taskId));
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.delivery_task_events WHERE event_key='test:created'"
                ))).isEqualTo(1);
            }
            app.rollback();

            // UPDATE/DELETE are rejected even under the right tenant; each failing
            // statement needs its own transaction because PostgreSQL aborts it.
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThatThrownBy(() -> stmt.execute(
                        "UPDATE app.delivery_task_events SET event_type='SKIPPED' WHERE event_key='test:created'"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();
            app.setAutoCommit(false);
            try (var stmt = app.createStatement()) {
                stmt.execute("SELECT set_config('app.current_user_id', '" + userA + "', true)");
                assertThatThrownBy(() -> stmt.execute(
                        "DELETE FROM app.delivery_task_events WHERE event_key='test:created'"
                )).isInstanceOf(SQLException.class);
            }
            app.rollback();
        }
    }

    @Test
    @Order(11)
    void matchApplyAutoCreatesDeliveryTaskOnlyForBossAndZhilian() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID bossJob = UUID.randomUUID();
        UUID zhilianJob = UUID.randomUUID();
        UUID liepinJob = UUID.randomUUID();
        UUID bossMatch = UUID.randomUUID();
        UUID zhilianMatch = UUID.randomUUID();
        UUID liepinMatch = UUID.randomUUID();
        UUID reviewMatch = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-apply@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, user));
            stmt.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb)
                    """.formatted(preferenceId, user));
            for (Object[] job : new Object[][]{
                    {bossJob, "BOSS", "https://www.zhipin.com/job_detail/boss.html"},
                    {zhilianJob, "ZHILIAN", "https://sou.zhaopin.com/jobs/jobdetail/test"},
                    {liepinJob, "LIEPIN", "https://www.liepin.com/job/test"}}) {
                stmt.execute("""
                        INSERT INTO app.job_posts(
                            id, user_id, platform, fingerprint, title, company_name, job_url,
                            source_captured_at, last_seen_at
                        ) VALUES (
                            '%s', '%s', '%s', repeat('c', 64), 'Java工程师', '示例公司', '%s', now(), now()
                        )
                        """.formatted(job[0], user, job[1], job[2]));
            }
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint, greeting
                    ) VALUES
                    ('%s', '%s', '%s', '%s', '%s', 'PROCESSING', repeat('d', 64), '您好，我对贵司岗位很感兴趣'),
                    ('%s', '%s', '%s', '%s', '%s', 'PROCESSING', repeat('e', 64), NULL),
                    ('%s', '%s', '%s', '%s', '%s', 'PROCESSING', repeat('f', 64), 'Liepin 不需要招呼语'),
                    ('%s', '%s', '%s', '%s', '%s', 'PROCESSING', repeat('a1', 32), '仅评审')
                    """.formatted(bossMatch, user, bossJob, resumeId, preferenceId,
                            zhilianMatch, user, zhilianJob, resumeId, preferenceId,
                            liepinMatch, user, liepinJob, resumeId, preferenceId,
                            reviewMatch, user, bossJob, resumeId, preferenceId));
        }

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.job_matches SET status='SUCCEEDED', decision='APPLY', completed_at=now() " +
                    "WHERE id IN ('" + bossMatch + "', '" + zhilianMatch + "', '" + liepinMatch + "')");
            stmt.execute("UPDATE app.job_matches SET status='SUCCEEDED', decision='REVIEW', completed_at=now() " +
                    "WHERE id='" + reviewMatch + "'");

            // BOSS task copies the match greeting; ZHILIAN keeps null; LIEPIN creates nothing
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + bossMatch + "'"
            ))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT greeting FROM app.delivery_tasks WHERE job_match_id='" + bossMatch + "'"
            ))).isEqualTo("您好，我对贵司岗位很感兴趣");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + zhilianMatch + "'"
            ))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT greeting FROM app.delivery_tasks WHERE job_match_id='" + zhilianMatch + "'"
            ))).isNull();
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + liepinMatch + "'"
            ))).isZero();
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + reviewMatch + "'"
            ))).isZero();
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE job_match_id='" + bossMatch + "'"
            ))).isEqualTo("PENDING_CONFIRMATION");
            // A CREATED event is written next to each auto-created task
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events e JOIN app.delivery_tasks t " +
                            "ON t.id=e.delivery_task_id WHERE t.job_match_id='" + bossMatch + "' " +
                            "AND e.event_type='CREATED'"
            ))).isEqualTo(1);

            // Re-updating the same SUCCEEDED+APPLY match never duplicates the task
            stmt.execute("UPDATE app.job_matches SET summary='再次更新' WHERE id='" + bossMatch + "'");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + bossMatch + "'"
            ))).isEqualTo(1);

            // A second APPLY match for the same BOSS job conflicts with the active-task index
            UUID secondMatch = UUID.randomUUID();
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint, greeting
                    ) VALUES ('%s', '%s', '%s', '%s', '%s', 'PROCESSING', repeat('b1', 32), '第二轮')
                    """.formatted(secondMatch, user, bossJob, resumeId, preferenceId));
            stmt.execute("UPDATE app.job_matches SET status='SUCCEEDED', decision='APPLY', completed_at=now() " +
                    "WHERE id='" + secondMatch + "'");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_post_id='" + bossJob + "'"
            ))).isEqualTo(1);
        }
    }

    @Test
    @Order(12)
    void pluginBindRotatesTokensEnforcesDeviceCapAndAuthenticatesHashes() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-bind@example.com', '$argon2id$test')");
            stmt.execute("INSERT INTO app.user_profiles(user_id, display_name) VALUES " +
                    "('" + user + "', '绑定测试')");
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
             var stmt = app.createStatement()) {
            stmt.execute("SELECT set_config('app.current_user_id', '" + user + "', false)");
            String installationHash = repeat("a", 64);
            String tokenHash1 = repeat("b", 64);
            var bind = stmt.executeQuery(
                    "SELECT * FROM app.bind_plugin_device('" + user + "', '" + installationHash + "', " +
                            "'我的 Chrome', 'Chrome', '120', '1.2.0', '[\"BOSS\"]'::jsonb, " +
                            "'ajp_plg_11111111', '" + tokenHash1 + "', " +
                            "'[\"device:read\",\"tasks:read\",\"tasks:write\"]'::jsonb, now() + interval '90 days', 10)");
            assertThat(bind.next()).isTrue();
            assertThat(bind.getString("outcome")).isEqualTo("OK");
            UUID deviceId = bind.getObject("bound_device_id", UUID.class);
            assertThat(bind.getBoolean("device_reused")).isFalse();
            bind.close();

            // Re-binding the same installation reuses the device and rotates the old token
            String tokenHash2 = repeat("c", 64);
            var rebind = stmt.executeQuery(
                    "SELECT * FROM app.bind_plugin_device('" + user + "', '" + installationHash + "', " +
                            "'我的 Chrome 2', 'Chrome', '121', '1.3.0', '[\"BOSS\",\"ZHILIAN\"]'::jsonb, " +
                            "'ajp_plg_22222222', '" + tokenHash2 + "', " +
                            "'[\"device:read\",\"tasks:read\"]'::jsonb, now() + interval '90 days', 10)");
            assertThat(rebind.next()).isTrue();
            assertThat(rebind.getObject("bound_device_id", UUID.class)).isEqualTo(deviceId);
            assertThat(rebind.getBoolean("device_reused")).isTrue();
            rebind.close();
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.plugin_tokens WHERE token_hash='" + tokenHash1 + "'"
            ))).isEqualTo("REVOKED");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.plugin_devices WHERE user_id='" + user + "' AND status='ACTIVE'"
            ))).isEqualTo(1);

            // Device cap: only one more new installation fits under max=2
            stmt.execute("SELECT * FROM app.bind_plugin_device('" + user + "', '" + repeat("d", 64) + "', " +
                    "'第二台', 'Edge', '121', '1.3.0', '[\"BOSS\"]'::jsonb, 'ajp_plg_33333333', '" + repeat("e", 64) + "', " +
                    "'[\"device:read\"]'::jsonb, now() + interval '90 days', 2)");
            var limited = stmt.executeQuery(
                    "SELECT outcome FROM app.bind_plugin_device('" + user + "', '" + repeat("f", 64) + "', " +
                            "'第三台', 'Edge', '121', '1.3.0', '[\"BOSS\"]'::jsonb, 'ajp_plg_44444444', '" + repeat("a1", 32) + "', " +
                            "'[\"device:read\"]'::jsonb, now() + interval '90 days', 2)");
            assertThat(limited.next()).isTrue();
            assertThat(limited.getString(1)).isEqualTo("DEVICE_LIMIT_EXCEEDED");
            limited.close();

            // Token authentication returns the minimal trusted fields and never a hash column
            var authed = stmt.executeQuery(
                    "SELECT * FROM app.authenticate_plugin_token('ajp_plg_22222222', '" + tokenHash2 + "')");
            assertThat(authed.next()).isTrue();
            assertThat(authed.getObject("device_id", UUID.class)).isEqualTo(deviceId);
            assertThat(authed.getString("token_status")).isEqualTo("ACTIVE");
            assertThat(authed.getString("user_status")).isEqualTo("ACTIVE");
            assertThat(authed.getString("user_display_name")).isEqualTo("绑定测试");
            assertThat(authed.getString("device_status")).isEqualTo("ACTIVE");
            var authColumns = authed.getMetaData();
            for (int i = 1; i <= authColumns.getColumnCount(); i++) {
                assertThat(authColumns.getColumnName(i).toLowerCase()).doesNotContain("hash");
            }
            authed.close();

            // Unknown token yields an empty set
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.authenticate_plugin_token('ajp_plg_zzzzzzzz', '" + repeat("a2", 32) + "')"
            ))).isZero();

            // Same prefix with a different hash must not authenticate either
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.authenticate_plugin_token('ajp_plg_22222222', '" + repeat("e", 64) + "')"
            ))).isZero();

            // Revocation kills tokens and flips the device
            assertThat(singleString(stmt.executeQuery(
                    "SELECT app.revoke_plugin_device('" + user + "', '" + deviceId + "', '用户主动撤销')"
            ))).isEqualTo("t");
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.plugin_tokens WHERE token_hash='" + tokenHash2 + "'"
            ))).isEqualTo("REVOKED");
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.plugin_devices WHERE id='" + deviceId + "'"
            ))).isEqualTo("REVOKED");

            // Expired tokens are lazily marked and reported as EXPIRED
            String expiredHash = repeat("a3", 32);
            stmt.execute("INSERT INTO app.plugin_tokens(user_id, plugin_device_id, token_prefix, token_hash, " +
                    "scopes, status, expires_at, created_at) VALUES ('" + user + "', '" + deviceId + "', " +
                    "'ajp_plg_expired', '" + expiredHash + "', '[\"device:read\"]'::jsonb, 'ACTIVE', " +
                    "now() - interval '1 hour', now() - interval '2 hours')");
            var expired = stmt.executeQuery(
                    "SELECT * FROM app.authenticate_plugin_token('ajp_plg_expired', '" + expiredHash + "')");
            assertThat(expired.next()).isTrue();
            assertThat(expired.getString("token_status")).isEqualTo("EXPIRED");
            expired.close();
        }
    }

    @Test
    @Order(13)
    void pluginTaskStartIsAtomicAndEnforcesVersionDeviceAndAttemptLimits() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        UUID task = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-start@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(job, user));
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES
                    ('%s', '%s', '设备A', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb),
                    ('%s', '%s', '设备B', repeat('b', 64), '1.0.0', '["BOSS"]'::jsonb)
                    """.formatted(deviceA, user, deviceB, user));
            stmt.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, assigned_device_id, status,
                        idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'CONFIRMED',
                        repeat('d', 64), repeat('e', 64), now(), '%s'
                    )
                    """.formatted(task, user, job, deviceA, user));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
             var stmt = app.createStatement()) {
            String keyA = repeat("a1", 32);
            String keyB = repeat("b1", 32);
            // Wrong version is rejected before any state change
            assertThat(startOutcome(stmt, user, deviceA, task, 99, "exec-00000001", keyA, "hash-1"))
                    .isEqualTo("VERSION_CONFLICT");
            // A different device cannot claim a task assigned to device A
            assertThat(startOutcome(stmt, user, deviceB, task, 1, "exec-00000001", keyA, "hash-1"))
                    .isEqualTo("TASK_ALREADY_CLAIMED");
            // Correct device + version claims the task atomically
            var started = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + deviceA + "', '" + task + "', " +
                            "1, 'exec-00000001', '" + keyA + "', 'hash-1', 300, 3)");
            assertThat(started.next()).isTrue();
            assertThat(started.getString("outcome")).isEqualTo("OK");
            assertThat(started.getString("task_status")).isEqualTo("EXECUTING");
            assertThat(started.getObject("new_lease_id", UUID.class)).isNotNull();
            assertThat(started.getInt("attempt_number")).isEqualTo(1);
            assertThat(started.getInt("new_version")).isEqualTo(2);
            assertThat(started.getString("job_platform")).isEqualTo("BOSS");
            started.close();

            // Replaying the same start returns the live lease even with the old version
            var replay = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + deviceA + "', '" + task + "', " +
                            "1, 'exec-00000001', '" + keyA + "', 'hash-1', 300, 3)");
            assertThat(replay.next()).isTrue();
            assertThat(replay.getString("outcome")).isEqualTo("REPLAY");
            assertThat(replay.getObject("new_lease_id", UUID.class)).isNotNull();
            replay.close();

            // Same execution with a different payload conflicts instead of replaying
            var conflicting = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_start('" + user + "', '" + deviceA + "', '" + task + "', " +
                            "1, 'exec-00000001', '" + keyA + "', 'hash-different', 300, 3)");
            assertThat(conflicting.next()).isTrue();
            assertThat(conflicting.getString(1)).isEqualTo("IDEMPOTENCY_CONFLICT");
            conflicting.close();

            // Another device cannot steal the executing task
            assertThat(startOutcome(stmt, user, deviceB, task, 2, "exec-00000002", keyB, "hash-2"))
                    .isEqualTo("TASK_ALREADY_CLAIMED");
        }
    }

    @Test
    @Order(14)
    void pluginSuccessFailPauseAndLeaseRecoveryEnforceTerminalStates() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-flow@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES ('%s', '%s', '设备', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb)
                    """.formatted(device, user));
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
             var stmt = app.createStatement()) {
            stmt.execute("SELECT set_config('app.current_user_id', '" + user + "', false)");
            UUID successTask = seedConfirmedTask(ownerConnection(), user, device);
            UUID failTask = seedConfirmedTask(ownerConnection(), user, device);
            UUID expiredTask = seedConfirmedTask(ownerConnection(), user, device);
            String keySuccess = repeat("c1", 32);
            String keyFail = repeat("d1", 32);
            String keyPause = repeat("e1", 32);

            var successStart = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + successTask + "', " +
                            "1, 'exec-success-1', '" + repeat("a9", 32) + "', 'h', 300, 3)");
            assertThat(successStart.next()).isTrue();
            UUID successLease = successStart.getObject("new_lease_id", UUID.class);
            successStart.close();

            // Wrong lease is rejected
            var wrongLease = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_success('" + user + "', '" + device + "', '" + successTask + "', " +
                            "'" + UUID.randomUUID() + "', 'exec-success-1', 2, now(), 'DELIVERED', " +
                            "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + keySuccess + "', 'p-ok')");
            assertThat(wrongLease.next()).isTrue();
            assertThat(wrongLease.getString(1)).isEqualTo("LEASE_INVALID");
            wrongLease.close();

            // Correct lease succeeds; SUCCEEDED is terminal and replay-safe
            var success = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_success('" + user + "', '" + device + "', '" + successTask + "', " +
                            "'" + successLease + "', 'exec-success-1', 2, now(), 'DELIVERED', " +
                            "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + keySuccess + "', 'p-ok')");
            assertThat(success.next()).isTrue();
            assertThat(success.getString("outcome")).isEqualTo("OK");
            assertThat(success.getInt("new_version")).isEqualTo(3);
            success.close();
            var successReplay = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_success('" + user + "', '" + device + "', '" + successTask + "', " +
                            "'" + successLease + "', 'exec-success-1', 2, now(), 'DELIVERED', " +
                            "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + keySuccess + "', 'p-ok')");
            assertThat(successReplay.next()).isTrue();
            assertThat(successReplay.getString(1)).isEqualTo("REPLAY");
            successReplay.close();
            // Same execution with a different payload conflicts instead of replaying
            var successConflict = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_success('" + user + "', '" + device + "', '" + successTask + "', " +
                            "'" + successLease + "', 'exec-success-1', 2, now(), 'ALREADY_DELIVERED', " +
                            "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + keySuccess + "', 'p-other')");
            assertThat(successConflict.next()).isTrue();
            assertThat(successConflict.getString(1)).isEqualTo("IDEMPOTENCY_CONFLICT");
            successConflict.close();
            var failAfterSuccess = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_fail('" + user + "', '" + device + "', '" + successTask + "', " +
                            "'" + successLease + "', 'exec-success-1', 3, now(), 'NETWORK_ERROR', '重试', true, " +
                            "'" + repeat("f1", 32) + "', 'p-fail')");
            assertThat(failAfterSuccess.next()).isTrue();
            assertThat(failAfterSuccess.getString(1)).isEqualTo("INVALID_STATE");
            failAfterSuccess.close();
            // Exactly one SUCCEEDED event was written across the replay
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + successTask + "' " +
                            "AND event_type='SUCCEEDED'"
            ))).isEqualTo(1);

            // Fail flow: EXECUTING -> FAILED with retryability, then replay-safe
            var failStart = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + failTask + "', " +
                            "1, 'exec-fail-1', '" + repeat("b9", 32) + "', 'h', 300, 3)");
            assertThat(failStart.next()).isTrue();
            UUID failLease = failStart.getObject("new_lease_id", UUID.class);
            failStart.close();
            var failed = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_fail('" + user + "', '" + device + "', '" + failTask + "', " +
                            "'" + failLease + "', 'exec-fail-1', 2, now(), 'BUTTON_NOT_FOUND', '按钮不存在', true, " +
                            "'" + keyFail + "', 'p-fail')");
            assertThat(failed.next()).isTrue();
            assertThat(failed.getString("outcome")).isEqualTo("OK");
            assertThat(failed.getInt("attempt_number")).isEqualTo(1);
            failed.close();
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + failTask + "'"
            ))).isEqualTo("FAILED");
            assertThat(singleString(stmt.executeQuery(
                    "SELECT last_error_code FROM app.delivery_tasks WHERE id='" + failTask + "'"
            ))).isEqualTo("BUTTON_NOT_FOUND");

            // Pause flow: EXECUTING -> PAUSED, lease released
            UUID pauseTask = seedConfirmedTask(ownerConnection(), user, device);
            var pauseStart = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + pauseTask + "', " +
                            "1, 'exec-pause-1', '" + repeat("c9", 32) + "', 'h', 300, 3)");
            assertThat(pauseStart.next()).isTrue();
            UUID pauseLease = pauseStart.getObject("new_lease_id", UUID.class);
            pauseStart.close();
            var paused = stmt.executeQuery(
                    "SELECT outcome FROM app.plugin_task_pause('" + user + "', '" + device + "', '" + pauseTask + "', " +
                            "'" + pauseLease + "', 'exec-pause-1', 2, 'CAPTCHA_REQUIRED', '需要人工验证', " +
                            "'" + keyPause + "', 'p-pause')");
            assertThat(paused.next()).isTrue();
            assertThat(paused.getString(1)).isEqualTo("OK");
            paused.close();
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + pauseTask + "'"
            ))).isEqualTo("PAUSED");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE id='" + pauseTask + "' AND lease_id IS NULL"
            ))).isEqualTo(1);
            assertThat(startOutcome(stmt, user, device, pauseTask, 3, "exec-pause-2", repeat("a2", 32), "h"))
                    .isEqualTo("INVALID_STATE");

            // Lease expiry: under the attempt cap returns to CONFIRMED
            var expiredStart = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + expiredTask + "', " +
                            "1, 'exec-expired-1', '" + repeat("a3", 32) + "', 'h', 300, 3)");
            assertThat(expiredStart.next()).isTrue();
            expiredStart.close();
            try (Connection owner = ownerConnection(); var stmt2 = owner.createStatement()) {
                stmt2.execute("UPDATE app.delivery_tasks SET lease_expires_at = now() - interval '1 second' " +
                        "WHERE id='" + expiredTask + "'");
            }
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.recover_expired_delivery_leases(3)"
            ))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + expiredTask + "'"
            ))).isEqualTo("CONFIRMED");
            // Recovery releases the assigned device so any capable device can claim it.
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE id='" + expiredTask
                            + "' AND assigned_device_id IS NULL"
            ))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT event_type FROM app.delivery_task_events WHERE delivery_task_id='" + expiredTask + "' " +
                            "ORDER BY id DESC LIMIT 1"
            ))).isEqualTo("LEASE_EXPIRED");

            // Lease expiry at the attempt cap transitions to FAILED
            UUID exhaustedTask = seedConfirmedTask(ownerConnection(), user, device);
            try (Connection owner = ownerConnection(); var stmt2 = owner.createStatement()) {
                stmt2.execute("UPDATE app.delivery_tasks SET attempt_count=2 WHERE id='" + exhaustedTask + "'");
            }
            var exhaustedStart = stmt.executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + exhaustedTask + "', " +
                            "1, 'exec-exhausted-1', '" + repeat("a4", 32) + "', 'h', 300, 3)");
            assertThat(exhaustedStart.next()).isTrue();
            assertThat(exhaustedStart.getString("outcome")).isEqualTo("OK");
            exhaustedStart.close();
            try (Connection owner = ownerConnection(); var stmt2 = owner.createStatement()) {
                stmt2.execute("UPDATE app.delivery_tasks SET lease_expires_at = now() - interval '1 second' " +
                        "WHERE id='" + exhaustedTask + "'");
            }
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.recover_expired_delivery_leases(3)"
            ))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + exhaustedTask + "'"
            ))).isEqualTo("FAILED");
            assertThat(singleString(stmt.executeQuery(
                    "SELECT last_error_code FROM app.delivery_tasks WHERE id='" + exhaustedTask + "'"
            ))).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
            // The exhausted FAILED task also releases the assigned device.
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE id='" + exhaustedTask
                            + "' AND assigned_device_id IS NULL"
            ))).isEqualTo(1);
        }
    }

    @Test
    @Order(15)
    void deliveryTaskStatusConstraintsAndActiveJobUniquenessHold() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-constraints@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(job, user));
            // One active task per user + job: a second CONFIRMED row violates the partial index
            stmt.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, status, idempotency_key_hash, idempotency_payload_hash,
                        confirmed_at, confirmed_by
                    ) VALUES ('%s', '%s', '%s', 'CONFIRMED', repeat('d', 64), repeat('e', 64), now(), '%s')
                    """.formatted(UUID.randomUUID(), user, job, user));
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, " +
                            "idempotency_key_hash, idempotency_payload_hash) VALUES ('" + UUID.randomUUID() +
                            "', '" + user + "', '" + job + "', 'PENDING_CONFIRMATION', " +
                            "repeat('f', 64), repeat('a1', 32))"
            )).isInstanceOf(SQLException.class);
            // Greeting length is bounded in code points
            assertThatThrownBy(() -> stmt.execute(
                    "UPDATE app.delivery_tasks SET greeting=repeat('好', 61) WHERE user_id='" + user + "'"
            )).isInstanceOf(SQLException.class);
            // State/lease consistency CHECK: CONFIRMED cannot hold a lease
            assertThatThrownBy(() -> stmt.execute(
                    "UPDATE app.delivery_tasks SET lease_id='" + UUID.randomUUID() + "' WHERE user_id='" + user + "'"
            )).isInstanceOf(SQLException.class);
            // A second job isolates the following CHECK probes from the active-task index
            UUID checkJob = UUID.randomUUID();
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('a5', 32), '约束测试', '示例公司',
                        'https://www.zhipin.com/job_detail/check.html', now(), now()
                    )
                    """.formatted(checkJob, user));
            // Status/confirmation consistency: LEASED without confirmation is rejected
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, " +
                            "idempotency_key_hash, idempotency_payload_hash) VALUES ('" + UUID.randomUUID() +
                            "', '" + user + "', '" + checkJob + "', 'LEASED', " +
                            "repeat('a1', 32), repeat('a2', 32))"
            )).isInstanceOf(SQLException.class);
            // Execution consistency: EXECUTING requires execution id and an assigned device
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, " +
                            "idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by, " +
                            "lease_id, leased_at, lease_expires_at) VALUES ('" + UUID.randomUUID() +
                            "', '" + user + "', '" + checkJob + "', 'EXECUTING', " +
                            "repeat('b1', 32), repeat('b2', 32), now(), '" + user + "', " +
                            "'" + UUID.randomUUID() + "', now(), now() + interval '5 minutes')"
            )).isInstanceOf(SQLException.class);
            // Terminal consistency: SUCCEEDED requires a finished timestamp
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, " +
                            "idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by) " +
                            "VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + checkJob + "', 'SUCCEEDED', " +
                            "repeat('c1', 32), repeat('c2', 32), now(), '" + user + "')"
            )).isInstanceOf(SQLException.class);
        }
    }

    private static String startOutcome(
            java.sql.Statement stmt, UUID user, UUID device, UUID task,
            int version, String executionId, String keyHash, String payloadHash
    ) throws SQLException {
        var result = stmt.executeQuery(
                "SELECT outcome FROM app.plugin_task_start('" + user + "', '" + device + "', '" + task + "', " +
                        version + ", '" + executionId + "', '" + keyHash + "', '" + payloadHash + "', 300, 3)");
        try (result) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    @Test
    @Order(16)
    void concurrentFinishSerializesOnTheTaskRowAndTerminalStateWins() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-race1@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES ('%s', '%s', '设备', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb)
                    """.formatted(device, user));
        }
        UUID task = seedConfirmedTask(ownerConnection(), user, device);

        try (Connection a = appConnection(); Connection b = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            var started = a.createStatement().executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + device + "', '" + task + "', " +
                            "1, 'exec-race-1', '" + repeat("a9", 32) + "', 'p-start', 300, 3)");
            assertThat(started.next()).isTrue();
            UUID lease = started.getObject("new_lease_id", UUID.class);
            started.close();
            a.commit();

            // A commits a success first; the racing fail blocks on the row lock.
            a.setAutoCommit(false);
            var success = a.createStatement().executeQuery(
                    "SELECT outcome FROM app.plugin_task_success('" + user + "', '" + device + "', '" + task + "', " +
                            "'" + lease + "', 'exec-race-1', 2, now(), 'DELIVERED', " +
                            "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + repeat("a1", 32) + "', 'p-succ')");
            assertThat(success.next()).isTrue();
            assertThat(success.getString(1)).isEqualTo("OK");
            success.close();

            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> failOutcome = pool.submit(() -> {
                    try (var stmt = b.createStatement()) {
                        var rs = stmt.executeQuery(
                                "SELECT outcome FROM app.plugin_task_fail('" + user + "', '" + device + "', '" + task + "', " +
                                        "'" + lease + "', 'exec-race-1', 3, now(), 'NETWORK_ERROR', '竞态失败', true, " +
                                        "'" + repeat("b1", 32) + "', 'p-fail')");
                        rs.next();
                        String outcome = rs.getString(1);
                        rs.close();
                        return outcome;
                    }
                });
                Thread.sleep(500);
                assertThat(failOutcome.isDone()).isFalse();
                a.commit();
                assertThat(failOutcome.get(10, TimeUnit.SECONDS)).isEqualTo("INVALID_STATE");
                b.rollback();
            } finally {
                pool.shutdownNow();
            }

            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                assertThat(singleString(stmt.executeQuery(
                        "SELECT status FROM app.delivery_tasks WHERE id='" + task + "'"
                ))).isEqualTo("SUCCEEDED");
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + task + "' " +
                                "AND event_type IN ('FAILED','PAUSED')"
                ))).isZero();
            }
        }
    }

    @Test
    @Order(17)
    void concurrentStartBetweenTwoDevicesHasASingleWinnerThatBindsTheDevice() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-race2@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(job, user));
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES
                    ('%s', '%s', '设备A', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb),
                    ('%s', '%s', '设备B', repeat('b', 64), '1.0.0', '["BOSS"]'::jsonb)
                    """.formatted(deviceA, user, deviceB, user));
            // Unassigned CONFIRMED task: the first device to start wins.
            stmt.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, status,
                        idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by
                    ) VALUES (
                        '%s', '%s', '%s', 'CONFIRMED',
                        repeat('d', 64), repeat('e', 64), now(), '%s'
                    )
                    """.formatted(task, user, job, user));
        }

        try (Connection a = appConnection(); Connection b = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            var winner = a.createStatement().executeQuery(
                    "SELECT * FROM app.plugin_task_start('" + user + "', '" + deviceA + "', '" + task + "', " +
                            "1, 'exec-win-a', '" + repeat("a1", 32) + "', 'p-a', 300, 3)");
            assertThat(winner.next()).isTrue();
            assertThat(winner.getString("outcome")).isEqualTo("OK");
            UUID leaseA = winner.getObject("new_lease_id", UUID.class);
            winner.close();

            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> loser = pool.submit(() -> {
                    try (var stmt = b.createStatement()) {
                        var rs = stmt.executeQuery(
                                "SELECT outcome FROM app.plugin_task_start('" + user + "', '" + deviceB + "', '" + task + "', " +
                                        "1, 'exec-lose-b', '" + repeat("b1", 32) + "', 'p-b', 300, 3)");
                        rs.next();
                        String outcome = rs.getString(1);
                        rs.close();
                        return outcome;
                    }
                });
                Thread.sleep(500);
                assertThat(loser.isDone()).isFalse();
                a.commit();
                String loserOutcome = loser.get(10, TimeUnit.SECONDS);
                assertThat(loserOutcome).isIn("VERSION_CONFLICT", "TASK_ALREADY_CLAIMED");
                b.rollback();
            } finally {
                pool.shutdownNow();
            }

            // The winner bound the device; the loser cannot report back even with
            // the winner's lease and execution id at the current version.
            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                assertThat(singleString(stmt.executeQuery(
                        "SELECT assigned_device_id::text FROM app.delivery_tasks WHERE id='" + task + "'"
                ))).isEqualTo(deviceA.toString());
            }
            try (var stmt = b.createStatement()) {
                var rejected = stmt.executeQuery(
                        "SELECT outcome FROM app.plugin_task_success('" + user + "', '" + deviceB + "', '" + task + "', " +
                                "'" + leaseA + "', 'exec-win-a', 2, now(), 'DELIVERED', " +
                                "'{\"pageState\":\"SUCCESS_NOTICE\"}'::jsonb, '" + repeat("c1", 32) + "', 'p-c')");
                assertThat(rejected.next()).isTrue();
                assertThat(rejected.getString(1)).isEqualTo("LEASE_INVALID");
                rejected.close();
            }
        }
    }

    @Test
    @Order(18)
    void concurrentBindsSerializeOnTheUserRowAndHonorTheDeviceCap() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-cap@example.com', '$argon2id$test')");
        }

        try (Connection a = appConnection(); Connection b = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            var first = a.createStatement().executeQuery(
                    "SELECT outcome FROM app.bind_plugin_device('" + user + "', '" + repeat("a1", 32) + "', " +
                            "'第一台', 'Chrome', '120', '1.0.0', '[\"BOSS\"]'::jsonb, 'ajp_plg_11111111', '" + repeat("a2", 32) + "', " +
                            "'[\"device:read\"]'::jsonb, now() + interval '90 days', 1)");
            assertThat(first.next()).isTrue();
            assertThat(first.getString(1)).isEqualTo("OK");
            first.close();

            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> second = pool.submit(() -> {
                    try (var stmt = b.createStatement()) {
                        var rs = stmt.executeQuery(
                                "SELECT outcome FROM app.bind_plugin_device('" + user + "', '" + repeat("b1", 32) + "', " +
                                        "'第二台', 'Edge', '121', '1.1.0', '[\"BOSS\"]'::jsonb, 'ajp_plg_22222222', '" + repeat("b2", 32) + "', " +
                                        "'[\"device:read\"]'::jsonb, now() + interval '90 days', 1)");
                        rs.next();
                        String outcome = rs.getString(1);
                        rs.close();
                        return outcome;
                    }
                });
                Thread.sleep(500);
                assertThat(second.isDone()).isFalse();
                a.commit();
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("DEVICE_LIMIT_EXCEEDED");
                b.rollback();
            } finally {
                pool.shutdownNow();
            }

            try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
                assertThat(singleLong(stmt.executeQuery(
                        "SELECT count(*) FROM app.plugin_devices WHERE user_id='" + user + "' AND status='ACTIVE'"
                ))).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(19)
    void disabledAccountCannotBindNewDevicesOrTokens() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash, status) VALUES " +
                    "('" + user + "', 'r6-disabled@example.com', '$argon2id$test', 'DISABLED')");
        }

        try (Connection app = appConnection(); var stmt = app.createStatement()) {
            var bind = stmt.executeQuery(
                    "SELECT * FROM app.bind_plugin_device('" + user + "', '" + repeat("a1", 32) + "', " +
                            "'禁用设备', 'Chrome', '120', '1.0.0', '[\"BOSS\"]'::jsonb, 'ajp_plg_11111111', '" + repeat("a2", 32) + "', " +
                            "'[\"device:read\"]'::jsonb, now() + interval '90 days', 10)");
            assertThat(bind.next()).isTrue();
            assertThat(bind.getString("outcome")).isEqualTo("ACCOUNT_DISABLED");
            assertThat(bind.getObject("bound_device_id", UUID.class)).isNull();
            bind.close();
        }

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.plugin_devices WHERE user_id='" + user + "'"
            ))).isZero();
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.plugin_tokens WHERE user_id='" + user + "'"
            ))).isZero();
        }
    }

    @Test
    @Order(20)
    void deliveryTaskStateChecksRejectForgedInconsistentFields() throws Exception {
        configuredFlyway().migrate();
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + user + "', 'r6-forged@example.com', '$argon2id$test')");
            stmt.execute("""
                    INSERT INTO app.plugin_devices(
                        id, user_id, device_name, installation_id_hash, extension_version, capabilities
                    ) VALUES ('%s', '%s', '设备', repeat('a', 64), '1.0.0', '["BOSS"]'::jsonb)
                    """.formatted(device, user));
        }
        // Every probe targets its own job so the active-task unique index can
        // never mask the CHECK constraint under test.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            // PENDING_CONFIRMATION carrying an assigned device
            UUID job1 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, assigned_device_id, status, "
                            + "idempotency_key_hash, idempotency_payload_hash) VALUES ('" + UUID.randomUUID()
                            + "', '" + user + "', '" + job1 + "', '" + device + "', 'PENDING_CONFIRMATION', "
                            + "repeat('a1', 32), repeat('a2', 32))"
            )).isInstanceOf(SQLException.class);
            // PENDING_CONFIRMATION carrying an execution id
            UUID job2 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, execution_id, "
                            + "idempotency_key_hash, idempotency_payload_hash) VALUES ('" + UUID.randomUUID()
                            + "', '" + user + "', '" + job2 + "', 'PENDING_CONFIRMATION', 'exec-00000001', "
                            + "repeat('b1', 32), repeat('b2', 32))"
            )).isInstanceOf(SQLException.class);
            // CONFIRMED carrying an execution id
            UUID job3 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, execution_id, "
                            + "idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + job3 + "', 'CONFIRMED', "
                            + "'exec-00000001', repeat('c1', 32), repeat('c2', 32), now(), '" + user + "')"
            )).isInstanceOf(SQLException.class);
            // LEASED with confirmation and a lease but no assigned device
            UUID job4 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, "
                            + "idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by, "
                            + "lease_id, leased_at, lease_expires_at) VALUES ('" + UUID.randomUUID()
                            + "', '" + user + "', '" + job4 + "', 'LEASED', repeat('d1', 32), repeat('d2', 32), "
                            + "now(), '" + user + "', '" + UUID.randomUUID() + "', now(), now() + interval '5 minutes')"
            )).isInstanceOf(SQLException.class);
            // SKIPPED carrying stale confirmation fields
            UUID job5 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, "
                            + "idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by, finished_at) "
                            + "VALUES ('" + UUID.randomUUID() + "', '" + user + "', '" + job5 + "', 'SKIPPED', "
                            + "repeat('e1', 32), repeat('e2', 32), now(), '" + user + "', now())"
            )).isInstanceOf(SQLException.class);
            // SKIPPED carrying an assigned device
            UUID job6 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, assigned_device_id, status, "
                            + "idempotency_key_hash, idempotency_payload_hash, finished_at) VALUES ('" + UUID.randomUUID()
                            + "', '" + user + "', '" + job6 + "', '" + device + "', 'SKIPPED', "
                            + "repeat('f1', 32), repeat('f2', 32), now())"
            )).isInstanceOf(SQLException.class);
            // CANCELLED carrying an execution id
            UUID job7 = seedJobRow(owner, user);
            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO app.delivery_tasks(id, user_id, job_post_id, status, execution_id, "
                            + "idempotency_key_hash, idempotency_payload_hash, finished_at) VALUES ('" + UUID.randomUUID()
                            + "', '" + user + "', '" + job7 + "', 'CANCELLED', 'exec-00000001', "
                            + "repeat('a3', 32), repeat('a4', 32), now())"
            )).isInstanceOf(SQLException.class);
        }
    }

    /** Seeds a fresh BOSS job row (no matches) for CHECK-constraint probes. */
    private static UUID seedJobRow(Connection owner, UUID user) throws SQLException {
        UUID job = UUID.randomUUID();
        try (var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', '%s', 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(job, user,
                    repeat(UUID.randomUUID().toString().replace("-", "").substring(0, 32), 2).substring(0, 64)));
        }
        return job;
    }

    /** Seeds a fresh BOSS job plus a CONFIRMED task assigned to the device. */
    private static UUID seedConfirmedTask(
            Connection owner, UUID user, UUID device
    ) throws SQLException {
        UUID job = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        try (var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', '%s', 'Java工程师', '示例公司',
                        'https://www.zhipin.com/job_detail/test.html', now(), now()
                    )
                    """.formatted(job, user,
                    repeat(UUID.randomUUID().toString().replace("-", "").substring(0, 32), 2).substring(0, 64)));
            stmt.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, assigned_device_id, status,
                        idempotency_key_hash, idempotency_payload_hash, confirmed_at, confirmed_by
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'CONFIRMED',
                        '%s', '%s', now(), '%s'
                    )
                    """.formatted(task, user, job, device,
                    repeat(UUID.randomUUID().toString().replace("-", "").substring(0, 32), 2).substring(0, 64),
                    repeat(UUID.randomUUID().toString().replace("-", "").substring(0, 32), 2).substring(0, 64),
                    user));
        }
        return task;
    }

    private static String repeat(String value, int count) {
        return value.repeat(count);
    }
}
