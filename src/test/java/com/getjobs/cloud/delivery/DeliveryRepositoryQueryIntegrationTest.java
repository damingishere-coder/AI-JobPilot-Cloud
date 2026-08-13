package com.getjobs.cloud.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskListRow;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskQuery;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskSort;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level integration tests for the fixed delivery task list/count
 * SQL against real PostgreSQL with RLS. After the CodeQL rework the SQL text
 * is static and every filter travels as a bind parameter, so these tests pin
 * the behaviour that must not change: pagination, filter semantics, the
 * status collection (an empty selection must never produce an invalid IN ())
 * and the sort branches.
 */
@Testcontainers
class DeliveryRepositoryQueryIntegrationTest {
    private static final String APP_USER = "jobpilot_app";
    private static final String APP_PASSWORD = "integration-app-password";
    private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    private static HikariDataSource dataSource;
    private static DeliveryRepository tasks;
    private static TransactionTemplate transactions;
    private static TenantContextExecutor tenants;

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
        tasks = new DeliveryRepository(jdbc, new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tenants = new TenantContextExecutor(jdbc);
    }

    @AfterAll
    static void stopRepository() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- pagination, default sort and joined mapping fields ----

    @Test
    void paginationDefaultsToCreatedDescendingAndMapsJoinedFields() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "tasks-page@example.com");
        UUID job1 = ids(user, "TASK J1");
        seedJob(user, job1, "BOSS", "Java 后端工程师", "A 公司");
        UUID matchId = seedMatch(user, job1, "SUCCEEDED", "APPLY", 88);
        UUID deviceId = ids(user, "DEVICE");
        seedDevice(user, deviceId, "我的 Chrome");
        for (int i = 1; i <= 5; i++) {
            Instant createdAt = BASE.plus(i, ChronoUnit.HOURS);
            UUID jobId = ids(user, "TASK J" + i);
            if (i > 1) {
                seedJob(user, jobId, "BOSS", "分页岗位" + i, "A 公司");
            }
            if (i == 1) {
                // Fully-attributed task: match + device + two events; the
                // lateral join must surface only the latest event.
                seedTask(user, "TASK 1", job1, matchId, deviceId, "SUCCEEDED", "问候语",
                        BASE.plus(1, ChronoUnit.HOURS), createdAt, createdAt, createdAt);
                seedEvent(user, ids(user, "TASK 1"), "CREATED", BASE.plus(1, ChronoUnit.HOURS));
                seedEvent(user, ids(user, "TASK 1"), "SUCCEEDED", BASE.plus(2, ChronoUnit.HOURS));
            } else {
                seedTask(user, "TASK " + i, jobId, null, null, "PENDING_CONFIRMATION", null,
                        null, createdAt, createdAt, null);
            }
        }

        TaskQuery query = query(1, 2, List.of(), null, null, null, null, null);
        assertThat(inTenant(user, () -> tasks.count(user, query))).isEqualTo(5);

        List<TaskListRow> page1 = inTenant(user, () -> tasks.list(user, query));
        List<TaskListRow> page2 = inTenant(user, () -> tasks.list(user,
                query(2, 2, List.of(), null, null, null, null, null)));
        List<TaskListRow> page3 = inTenant(user, () -> tasks.list(user,
                query(3, 2, List.of(), null, null, null, null, null)));

        assertThat(page1).extracting(TaskListRow::id)
                .containsExactly(ids(user, "TASK 5"), ids(user, "TASK 4"));
        assertThat(page2).extracting(TaskListRow::id)
                .containsExactly(ids(user, "TASK 3"), ids(user, "TASK 2"));
        assertThat(page3).extracting(TaskListRow::id)
                .containsExactly(ids(user, "TASK 1"));

        TaskListRow attributed = page3.get(0);
        assertThat(attributed.status()).isEqualTo("SUCCEEDED");
        assertThat(attributed.greeting()).isEqualTo("问候语");
        assertThat(attributed.job().id()).isEqualTo(job1);
        assertThat(attributed.job().platform()).isEqualTo("BOSS");
        assertThat(attributed.job().title()).isEqualTo("Java 后端工程师");
        assertThat(attributed.match().id()).isEqualTo(matchId);
        assertThat(attributed.match().score()).isEqualTo(88);
        assertThat(attributed.match().decision()).isEqualTo("APPLY");
        assertThat(attributed.device().id()).isEqualTo(deviceId);
        assertThat(attributed.device().deviceName()).isEqualTo("我的 Chrome");
        assertThat(attributed.lastEvent().eventType()).isEqualTo("SUCCEEDED");
    }

    // ---- status collection ----

    @Test
    void statusCollectionFiltersAndEmptySelectionIsSafe() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "tasks-status@example.com");
        seedTask(user, "TASK PENDING", "PENDING_CONFIRMATION", BASE);
        seedTask(user, "TASK CONFIRMED", "CONFIRMED", BASE.plus(1, ChronoUnit.HOURS));
        seedTask(user, "TASK SKIPPED", "SKIPPED", BASE.plus(2, ChronoUnit.HOURS));

        // Empty selection must return everything, never an invalid IN ().
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), null, null,
                null, null, null)))).isEqualTo(3);
        assertThat(inTenant(user, () -> tasks.list(user, query(1, 10, List.of(), null, null,
                null, null, null)))).hasSize(3);

        assertThat(inTenant(user, () -> tasks.list(user, query(1, 10, List.of("PENDING_CONFIRMATION"),
                null, null, null, null, null))))
                .extracting(TaskListRow::id).containsExactly(ids(user, "TASK PENDING"));
        List<TaskListRow> two = inTenant(user, () -> tasks.list(user, query(1, 10,
                List.of("CONFIRMED", "SKIPPED"), null, null, null, null, null)));
        assertThat(two).extracting(TaskListRow::id)
                .containsExactlyInAnyOrder(ids(user, "TASK CONFIRMED"), ids(user, "TASK SKIPPED"));
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10,
                List.of("CONFIRMED", "SKIPPED"), null, null, null, null, null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10,
                List.of("LEASED"), null, null, null, null, null)))).isZero();
    }

    // ---- filter values ----

    @Test
    void platformAndKeywordFiltersMatchBoundValues() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "tasks-filter@example.com");
        seedTask(user, "TASK BOSS", "BOSS", "Java 后端工程师", "A 公司", BASE);
        seedTask(user, "TASK ZHILIAN", "ZHILIAN", "产品经理", "B 公司", BASE.plus(1, ChronoUnit.HOURS));
        seedTask(user, "TASK LIEPIN", "LIEPIN", "Java 架构师", "C 公司", BASE.plus(2, ChronoUnit.HOURS));

        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), "BOSS", null,
                null, null, null)))).isEqualTo(1);
        assertThat(inTenant(user, () -> tasks.list(user, query(1, 10, List.of(), "BOSS", null,
                null, null, null))))
                .extracting(TaskListRow::id).containsExactly(ids(user, "TASK BOSS"));
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), null, "Java",
                null, null, null)))).isEqualTo(2);
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), "ZHILIAN", "Java",
                null, null, null)))).isZero();
        // Keyword matches company_name too.
        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), null, "C 公司",
                null, null, null)))).isEqualTo(1);
    }

    @Test
    void createdRangeFiltersRespectBounds() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "tasks-range@example.com");
        for (int i = 1; i <= 4; i++) {
            seedTask(user, "TASK RANGE " + i, "PENDING_CONFIRMATION", BASE.plus(i, ChronoUnit.DAYS));
        }

        assertThat(inTenant(user, () -> tasks.count(user, query(1, 10, List.of(), null, null,
                BASE.plus(2, ChronoUnit.DAYS), BASE.plus(3, ChronoUnit.DAYS), null)))).isEqualTo(2);
        List<TaskListRow> result = inTenant(user, () -> tasks.list(user, query(1, 10, List.of(),
                null, null, BASE.plus(3, ChronoUnit.DAYS), null, null)));
        assertThat(result).extracting(TaskListRow::id)
                .containsExactlyInAnyOrder(ids(user, "TASK RANGE 3"), ids(user, "TASK RANGE 4"));
    }

    // ---- sort enum ----

    @Test
    void sortEnumOrdersEverySupportedColumnWithNullsLast() throws Exception {
        UUID user = UUID.randomUUID();
        seedUser(user, "tasks-sort@example.com");
        seedTask(user, "TASK A", "SUCCEEDED", BASE, BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(4, ChronoUnit.HOURS));
        seedTask(user, "TASK B", "SUCCEEDED", BASE.plus(1, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(3, ChronoUnit.HOURS));
        seedTask(user, "TASK C", "SUCCEEDED", BASE.plus(2, ChronoUnit.HOURS),
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(1, ChronoUnit.HOURS));
        // PENDING_CONFIRMATION has confirmed_at NULL: always last in both directions.
        seedTask(user, "TASK D", "PENDING_CONFIRMATION", BASE.plus(3, ChronoUnit.HOURS),
                BASE.plus(3, ChronoUnit.HOURS), null);

        assertThat(sortedIds(user, TaskSort.CREATED_ASC)).containsExactly(
                ids(user, "TASK A"), ids(user, "TASK B"), ids(user, "TASK C"), ids(user, "TASK D"));
        assertThat(sortedIds(user, TaskSort.CREATED_DESC)).containsExactly(
                ids(user, "TASK D"), ids(user, "TASK C"), ids(user, "TASK B"), ids(user, "TASK A"));
        assertThat(sortedIds(user, TaskSort.UPDATED_ASC)).containsExactly(
                ids(user, "TASK B"), ids(user, "TASK C"), ids(user, "TASK D"), ids(user, "TASK A"));
        assertThat(sortedIds(user, TaskSort.UPDATED_DESC)).containsExactly(
                ids(user, "TASK A"), ids(user, "TASK D"), ids(user, "TASK C"), ids(user, "TASK B"));
        assertThat(sortedIds(user, TaskSort.CONFIRMED_ASC)).containsExactly(
                ids(user, "TASK C"), ids(user, "TASK B"), ids(user, "TASK A"), ids(user, "TASK D"));
        assertThat(sortedIds(user, TaskSort.CONFIRMED_DESC)).containsExactly(
                ids(user, "TASK A"), ids(user, "TASK B"), ids(user, "TASK C"), ids(user, "TASK D"));
    }

    private static List<UUID> sortedIds(UUID user, TaskSort sort) {
        return inTenant(user, () -> tasks.list(user, query(1, 10, List.of(), null, null,
                        null, null, sort)))
                .stream().map(TaskListRow::id).toList();
    }

    // ---- helpers ----

    private static <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private static TaskQuery query(int page, int size, List<String> statuses, String platform,
                                   String keyword, Instant from, Instant to, TaskSort sort) {
        return new TaskQuery(page, size, statuses, platform, keyword, from, to, sort);
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

    private static void seedJob(UUID user, UUID jobId, String platform, String title, String company)
            throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     INSERT INTO app.job_posts(
                         id, user_id, platform, fingerprint, title, company_name, job_url,
                         source_captured_at, last_seen_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, jobId);
            statement.setObject(2, user);
            statement.setString(3, platform);
            statement.setString(4, fingerprint(jobId));
            statement.setString(5, title);
            statement.setString(6, company);
            statement.setString(7, "https://www.zhipin.com/job_detail/" + jobId + ".html");
            statement.setObject(8, java.sql.Timestamp.from(BASE));
            statement.setObject(9, java.sql.Timestamp.from(BASE));
            statement.executeUpdate();
        }
    }

    private static void seedDevice(UUID user, UUID deviceId, String deviceName) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     INSERT INTO app.plugin_devices(
                         id, user_id, device_name, installation_id_hash, extension_version, capabilities, status
                     ) VALUES (?, ?, ?, ?, '1.0.0', '["BOSS"]'::jsonb, 'ACTIVE')
                     """)) {
            statement.setObject(1, deviceId);
            statement.setObject(2, user);
            statement.setString(3, deviceName);
            statement.setString(4, fingerprint(deviceId));
            statement.executeUpdate();
        }
    }

    /** Seeds a PENDING_CONFIRMATION task on its own freshly created job. */
    private static void seedTask(UUID user, String tag, String platform, String title, String company,
                                 Instant createdAt) throws SQLException {
        UUID jobId = ids(user, tag + "-JOB");
        seedJob(user, jobId, platform, title, company);
        seedTask(user, tag, jobId, null, null, "PENDING_CONFIRMATION", null,
                null, createdAt, createdAt, null);
    }

    /** Seeds a task on its own job; confirmation and terminal timestamps follow from the status. */
    private static void seedTask(UUID user, String tag, String status, Instant createdAt)
            throws SQLException {
        seedTask(user, tag, status, createdAt, createdAt, createdAt);
    }

    private static void seedTask(UUID user, String tag, String status, Instant createdAt,
                                 Instant updatedAt, Instant confirmedAt) throws SQLException {
        UUID jobId = ids(user, tag + "-JOB");
        seedJob(user, jobId, "BOSS", "默认岗位", "默认公司");
        Instant finishedAt = isTerminal(status) ? createdAt : null;
        seedTask(user, tag, jobId, null, null, status, null, confirmedAt, createdAt, updatedAt, finishedAt);
    }

    private static void seedTask(UUID user, String tag, UUID jobPostId, UUID jobMatchId, UUID assignedDeviceId,
                                 String status, String greeting, Instant confirmedAt,
                                 Instant createdAt, Instant updatedAt, Instant finishedAt)
            throws SQLException {
        UUID taskId = ids(user, tag);
        boolean confirmed = status.equals("CONFIRMED") || status.equals("SUCCEEDED")
                || status.equals("FAILED") || status.equals("PAUSED");
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     INSERT INTO app.delivery_tasks(
                         id, user_id, job_post_id, job_match_id, assigned_device_id, status, greeting,
                         confirmed_at, confirmed_by, idempotency_key_hash, idempotency_payload_hash,
                         created_at, updated_at, finished_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, taskId);
            statement.setObject(2, user);
            statement.setObject(3, jobPostId);
            statement.setObject(4, jobMatchId);
            statement.setObject(5, assignedDeviceId);
            statement.setString(6, status);
            statement.setString(7, greeting);
            statement.setObject(8, confirmed ? java.sql.Timestamp.from(confirmedAt) : null);
            statement.setObject(9, confirmed ? user : null);
            statement.setString(10, fingerprint(taskId));
            statement.setString(11, fingerprint(taskId));
            statement.setObject(12, java.sql.Timestamp.from(createdAt));
            statement.setObject(13, java.sql.Timestamp.from(updatedAt));
            statement.setObject(14, finishedAt == null ? null : java.sql.Timestamp.from(finishedAt));
            statement.executeUpdate();
        }
    }

    private static boolean isTerminal(String status) {
        return status.equals("SKIPPED") || status.equals("CANCELLED")
                || status.equals("SUCCEEDED") || status.equals("FAILED");
    }

    private static void seedEvent(UUID user, UUID taskId, String eventType, Instant createdAt)
            throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     INSERT INTO app.delivery_task_events(
                         user_id, delivery_task_id, event_type, from_status, to_status,
                         actor_type, actor_id, event_key, details, created_at
                     ) VALUES (?, ?, ?, NULL, ?, 'SYSTEM', NULL, ?, '{}'::jsonb, ?)
                     """)) {
            statement.setObject(1, user);
            statement.setObject(2, taskId);
            statement.setString(3, eventType);
            statement.setString(4, eventType.equals("CREATED") ? "PENDING_CONFIRMATION" : "SUCCEEDED");
            statement.setString(5, eventType.toLowerCase() + ":" + taskId);
            statement.setObject(6, java.sql.Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private static UUID seedMatch(UUID user, UUID jobPostId, String status, String decision, int score)
            throws SQLException {
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
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
                    "INSERT INTO app.job_preferences(id, user_id, version, is_current) VALUES (?, ?, 1, true)")) {
                preference.setObject(1, preferenceId);
                preference.setObject(2, user);
                preference.executeUpdate();
            }
            try (PreparedStatement match = owner.prepareStatement("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status, decision, score,
                        input_fingerprint, created_at, completed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
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
                match.executeUpdate();
            }
        }
        return matchId;
    }
}
