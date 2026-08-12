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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='app' " +
                            "AND table_name NOT IN ('flyway_schema_history')"
            ))).isEqualTo(8);
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
}
