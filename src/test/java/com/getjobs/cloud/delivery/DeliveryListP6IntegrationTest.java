package com.getjobs.cloud.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.CloudApplication;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6 HTTP-level integration tests for the delivery list + user confirmation
 * loop. Runs against Testcontainers PostgreSQL (real RLS app role) and Redis.
 * Execution stays disabled (the P6 default): confirmation stops at CONFIRMED
 * and every plugin task endpoint answers a safe EXECUTION_DISABLED error.
 */
@Testcontainers
@ActiveProfiles({"cloud", "api", "test"})
@SpringBootTest(
        classes = CloudApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/postgresql",
                "spring.flyway.default-schema=app",
                "spring.flyway.schemas=app",
                "spring.flyway.create-schemas=true",
                "spring.flyway.placeholders.app_role=jobpilot_app",
                "spring.flyway.user=jobpilot_owner",
                "app.auth.hash-pepper=p6-delivery-integration-pepper-32-bytes",
                "app.auth.login-ip-limit=1000",
                "app.auth.login-email-limit=1000",
                "app.auth.register-ip-limit=1000",
                "app.auth.csrf-ip-limit=1000",
                "app.plugin.bind-ip-limit=1000",
                "app.plugin.bind-code-attempt-limit=100",
                "app.ai-match.outbox-poll-delay=365d",
                "app.delivery.lease-sweep-delay=365d"
        }
)
class DeliveryListP6IntegrationTest {
    private static final String REDIS_PASSWORD = "integration_p6_delivery_redis";
    private static final String APP_PASSWORD = "integration-app-password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-p6-delivery-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password")
            .withInitScript("integration-init-app-role.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "jobpilot_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DeliveryService deliveryService;

    // ---- 1. Match-driven create, idempotency and per-match uniqueness ----

    @Test
    void createFromMatchReturnsWaitingConfirmAndEnforcesMatchUniqueness() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "p6-create@" + suffix() + ".example.com");

        UUID jobId = seedJob(userId, "BOSS", "Java 后端开发", "示例科技");
        UUID matchId = seedMatch(userId, jobId, "SUCCEEDED", "REVIEW", "您好，我对贵司岗位很感兴趣",
                "岗位与简历匹配度较高。", "[\"技术栈匹配\"]", "[\"薪资略低\"]");

        // jobMatchId drives the create; jobPostId is only cross-checked.
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks",
                "{\"jobMatchId\":\"" + matchId + "\",\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-1"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String taskId = json(created).at("/data/id").asText();
        assertThat(json(created).at("/data/status").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(created).at("/data/greeting").asText()).isEqualTo("您好，我对贵司岗位很感兴趣");

        // Same key replays; a different key for the same match still resolves
        // to the original task — one task per (user, match) forever.
        HttpResponse<String> sameKey = user.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + matchId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-1"));
        assertThat(sameKey.statusCode()).as(sameKey.body()).isEqualTo(201);
        assertThat(json(sameKey).at("/data/id").asText()).isEqualTo(taskId);
        HttpResponse<String> otherKey = user.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + matchId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-2"));
        assertThat(otherKey.statusCode()).as(otherKey.body()).isEqualTo(201);
        assertThat(json(otherKey).at("/data/id").asText()).isEqualTo(taskId);

        // A mismatched jobPostId is rejected before anything is written.
        UUID otherJob = seedJob(userId, "BOSS", "另一个岗位", "另一家公司");
        HttpResponse<String> mismatched = user.post(
                "/api/delivery/tasks",
                "{\"jobMatchId\":\"" + matchId + "\",\"jobPostId\":\"" + otherJob + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-3"));
        assertThat(mismatched.statusCode()).isEqualTo(422);
        assertThat(json(mismatched).at("/error/code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");

        // A reused key with a different payload conflicts stably.
        UUID secondMatch = seedMatch(userId, otherJob, "SUCCEEDED", "APPLY", null, "第二条匹配", "[]", "[]");
        HttpResponse<String> conflict = user.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + secondMatch + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-1"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Exactly one task row exists for the match in the database.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var rows = stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE user_id='" + userId
                            + "' AND job_match_id='" + matchId + "'");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(1);
        }

        // A non-SUCCEEDED match cannot enter the list.
        UUID pendingJob = seedJob(userId, "BOSS", "待分析岗位", "示例科技");
        UUID pendingMatch = seedMatch(userId, pendingJob, "PENDING", null, null, null, null, null);
        HttpResponse<String> notReady = user.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + pendingMatch + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-create-4"));
        assertThat(notReady.statusCode()).isEqualTo(422);
        assertThat(json(notReady).at("/error/code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    // ---- 2. List filters and cross-user isolation ----

    @Test
    void listFiltersStayUserScopedAndCrossUserAccessIsAUnified404() throws Exception {
        BrowserSession userA = new BrowserSession();
        BrowserSession userB = new BrowserSession();
        UUID userAId = register(userA, "p6-list-a@" + suffix() + ".example.com");
        UUID userBId = register(userB, "p6-list-b@" + suffix() + ".example.com");

        UUID jobA1 = seedJob(userAId, "BOSS", "Java 后端开发", "甲科技");
        UUID matchA1 = seedMatch(userAId, jobA1, "SUCCEEDED", "REVIEW", "甲问候", "匹配结论甲", "[]", "[]");
        UUID jobA2 = seedJob(userAId, "ZHILIAN", "产品经理", "乙网络");
        UUID matchA2 = seedMatch(userAId, jobA2, "SUCCEEDED", "APPLY", null, "匹配结论乙", "[]", "[]");
        String taskA1 = createTask(userA, matchA1, jobA1, "p6-list-a1");
        String taskA2 = createTask(userA, matchA2, jobA2, "p6-list-a2");

        UUID jobB = seedJob(userBId, "BOSS", "B 的岗位", "B 公司");
        UUID matchB = seedMatch(userBId, jobB, "SUCCEEDED", "APPLY", null, "B 的结论", "[]", "[]");
        String taskB = createTask(userB, matchB, jobB, "p6-list-b");

        // A sees exactly its own two tasks with P6 statuses and match evidence.
        HttpResponse<String> listA = userA.get("/api/delivery/tasks");
        assertThat(listA.statusCode()).isEqualTo(200);
        assertThat(json(listA).at("/data/total").asLong()).isEqualTo(2);
        assertThat(json(listA).at("/data/items").toString())
                .contains(taskA1, taskA2).doesNotContain(taskB, "B 的岗位");
        assertThat(json(listA).at("/data/items/0/status").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(listA).at("/data/items/0/lastEvent/toStatus").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(listA).at("/data/items/0/match/summary").asText()).isNotNull();
        // Salary is always present as an object; unseeded rows carry null fields.
        assertThat(json(listA).at("/data/items/0/job/salary/minK").isNull()).isTrue();

        // Status filter: P6 name and legacy name both work; unknown values 400.
        assertThat(json(userA.get("/api/delivery/tasks?status=WAITING_CONFIRM")).at("/data/total").asLong())
                .isEqualTo(2);
        assertThat(json(userA.get("/api/delivery/tasks?status=PENDING_CONFIRMATION")).at("/data/total").asLong())
                .isEqualTo(2);
        assertThat(json(userA.get("/api/delivery/tasks?status=CONFIRMED")).at("/data/total").asLong())
                .isZero();
        assertThat(userA.get("/api/delivery/tasks?status=EXECUTING_BOGUS").statusCode()).isEqualTo(400);

        // Platform, keyword and recommendation filters.
        assertThat(json(userA.get("/api/delivery/tasks?platform=BOSS")).at("/data/total").asLong())
                .isEqualTo(1);
        assertThat(json(userA.get("/api/delivery/tasks?keyword=" + java.net.URLEncoder.encode("产品经理", "UTF-8")))
                .at("/data/total").asLong()).isEqualTo(1);
        assertThat(json(userA.get("/api/delivery/tasks?recommendation=REVIEW")).at("/data/total").asLong())
                .isEqualTo(1);
        assertThat(json(userA.get("/api/delivery/tasks?recommendation=SKIP")).at("/data/total").asLong())
                .isZero();

        // A cannot read/confirm/skip/edit B's task — unified 404 without leaking ids.
        assertThat(userA.get("/api/delivery/tasks/" + taskB).statusCode()).isEqualTo(404);
        assertThat(userA.post("/api/delivery/tasks/" + taskB + "/confirm",
                "{\"version\":1,\"acknowledged\":true}", userA.csrf(),
                Map.of("Idempotency-Key", "p6-x-confirm")).statusCode()).isEqualTo(404);
        assertThat(userA.post("/api/delivery/tasks/" + taskB + "/skip",
                "{\"version\":1,\"reason\":\"越权\"}", userA.csrf(),
                Map.of("Idempotency-Key", "p6-x-skip")).statusCode()).isEqualTo(404);
        assertThat(userA.put("/api/delivery/tasks/" + taskB + "/greeting",
                "{\"version\":1,\"greeting\":\"越权\"}", userA.csrf()).statusCode()).isEqualTo(404);

        // A cannot create a task from B's match — 404, never a 422/409.
        HttpResponse<String> foreignCreate = userA.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + matchB + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "p6-foreign-create"));
        assertThat(foreignCreate.statusCode()).isEqualTo(404);
        assertThat(json(foreignCreate).at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(foreignCreate.body()).doesNotContain(matchB.toString(), taskB);
    }

    // ---- 3. Confirm / skip / greeting edits with P6 statuses and append-only events ----

    @Test
    void confirmSkipAndGreetingEditsFollowP6StatusesAndAppendOnlyEvents() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "p6-flow@" + suffix() + ".example.com");

        UUID jobId = seedJob(userId, "BOSS", "确认流岗位", "示例科技");
        UUID matchId = seedMatch(userId, jobId, "SUCCEEDED", "APPLY", "原始招呼语", "确认流结论",
                "[\"优势一\",\"优势二\"]", "[\"风险一\"]");
        String taskId = createTask(user, matchId, jobId, "p6-flow-create");

        // Detail carries the full match evidence and P6 statuses on events.
        HttpResponse<String> detail = user.get("/api/delivery/tasks/" + taskId);
        assertThat(json(detail).at("/data/status").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(detail).at("/data/match/summary").asText()).isEqualTo("确认流结论");
        assertThat(json(detail).at("/data/match/strengths").toString()).contains("优势一");
        assertThat(json(detail).at("/data/match/risks").toString()).contains("风险一");
        assertThat(json(detail).at("/data/events/0/eventType").asText()).isEqualTo("CREATED");
        assertThat(json(detail).at("/data/events/0/toStatus").asText()).isEqualTo("WAITING_CONFIRM");

        // Greeting edit while unconfirmed: still WAITING_CONFIRM (the task
        // keeps awaiting confirmation, so confirmationRequired stays true).
        HttpResponse<String> edited = user.put("/api/delivery/tasks/" + taskId + "/greeting",
                "{\"version\":1,\"greeting\":\"修改后的招呼语\"}", user.csrf());
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(json(edited).at("/data/status").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(edited).at("/data/confirmationRequired").asBoolean()).isTrue();
        int version2 = json(edited).at("/data/version").asInt();

        // Confirm: CONFIRMED with exactly one CONFIRMED event even on replay.
        HttpResponse<String> confirmed = user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":" + version2 + ",\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "p6-confirm-1"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(confirmed).at("/data/confirmationVersion").asInt()).isEqualTo(1);
        assertThat(json(confirmed).at("/data/assignedDeviceId").isNull()).isTrue();
        int version3 = json(confirmed).at("/data/version").asInt();
        assertThat(user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":" + version2 + ",\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "p6-confirm-1")).statusCode()).isEqualTo(200);

        // Editing the confirmed greeting invalidates the confirmation.
        HttpResponse<String> editedAfter = user.put("/api/delivery/tasks/" + taskId + "/greeting",
                "{\"version\":" + version3 + ",\"greeting\":\"再次修改\"}", user.csrf());
        assertThat(json(editedAfter).at("/data/status").asText()).isEqualTo("WAITING_CONFIRM");
        assertThat(json(editedAfter).at("/data/confirmationRequired").asBoolean()).isTrue();
        int version4 = json(editedAfter).at("/data/version").asInt();

        HttpResponse<String> detailAfter = user.get("/api/delivery/tasks/" + taskId);
        String events = json(detailAfter).at("/data/events").toString();
        assertThat(events).contains("GREETING_UPDATED", "CONFIRMATION_INVALIDATED", "CONFIRMED");

        // Confirm again, then skip with a reason.
        assertThat(user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":" + version4 + ",\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "p6-confirm-2")).statusCode()).isEqualTo(200);
        HttpResponse<String> rejectedSensitiveReason = user.post("/api/delivery/tasks/" + taskId + "/skip",
                "{\"version\":" + (version4 + 1) + ",\"reason\":\"Cookie: session=secret\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-skip-sensitive"));
        assertThat(rejectedSensitiveReason.statusCode()).isEqualTo(400);
        assertThat(json(rejectedSensitiveReason).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        HttpResponse<String> skipped = user.post("/api/delivery/tasks/" + taskId + "/skip",
                "{\"version\":" + (version4 + 1) + ",\"reason\":\"不感兴趣\"}",
                user.csrf(), Map.of("Idempotency-Key", "p6-skip-1"));
        assertThat(skipped.statusCode()).as(skipped.body()).isEqualTo(200);
        assertThat(json(skipped).at("/data/status").asText()).isEqualTo("SKIPPED");
        assertThat(json(skipped).at("/data/finishedAt").isNull()).isFalse();

        // Events are append-only: two real confirms (before and after the
        // greeting edit; the same-key replay wrote nothing extra) + exactly
        // one SKIPPED; the persistent status uses the canonical vocabulary.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId
                            + "' AND event_type='CONFIRMED'"))).isEqualTo(2);
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId
                            + "' AND event_type='SKIPPED'"))).isEqualTo(1);
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + taskId + "'"))).isEqualTo("SKIPPED");
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId
                            + "' AND event_type='CREATED'"))).isEqualTo(1);
        }
    }

    // ---- 4. Global summary, filters and the /{id} route ----

    @Test
    void summaryIsGlobalUserScopedFilteredAndNeverInterceptedByIdRoute() throws Exception {
        BrowserSession userA = new BrowserSession();
        BrowserSession userB = new BrowserSession();
        UUID userAId = register(userA, "p6-summary-a@" + suffix() + ".example.com");
        UUID userBId = register(userB, "p6-summary-b@" + suffix() + ".example.com");

        // WAITING_CONFIRM: create only.
        UUID job1 = seedJob(userAId, "BOSS", "待确认岗位", "甲科技");
        UUID match1 = seedMatch(userAId, job1, "SUCCEEDED", "APPLY", null, "结论一", "[]", "[]");
        String task1 = createTask(userA, match1, job1, "p6-sum-1");
        // CONFIRMED: create + confirm.
        UUID job2 = seedJob(userAId, "ZHILIAN", "已确认岗位", "乙网络");
        UUID match2 = seedMatch(userAId, job2, "SUCCEEDED", "REVIEW", null, "结论二", "[]", "[]");
        String task2 = createTask(userA, match2, job2, "p6-sum-2");
        assertThat(userA.post("/api/delivery/tasks/" + task2 + "/confirm",
                "{\"version\":1,\"acknowledged\":true}", userA.csrf(),
                Map.of("Idempotency-Key", "p6-sum-confirm")).statusCode()).isEqualTo(200);
        // SKIPPED: create + skip.
        UUID job3 = seedJob(userAId, "BOSS", "已跳过岗位", "丙软件");
        UUID match3 = seedMatch(userAId, job3, "SUCCEEDED", "SKIP", null, "结论三", "[]", "[]");
        String task3 = createTask(userA, match3, job3, "p6-sum-3");
        assertThat(userA.post("/api/delivery/tasks/" + task3 + "/skip",
                "{\"version\":1,\"reason\":\"跳过\"}", userA.csrf(),
                Map.of("Idempotency-Key", "p6-sum-skip")).statusCode()).isEqualTo(200);
        // PAUSED_NEED_USER: a paused task seeded directly (canonical status).
        UUID job4 = seedJob(userAId, "BOSS", "需处理岗位", "丁数据");
        UUID match4 = seedMatch(userAId, job4, "SUCCEEDED", "APPLY", null, "结论四", "[]", "[]");
        seedPausedTask(userAId, job4, match4);

        // B creates one task of its own; A's summary must never count it.
        UUID jobB = seedJob(userBId, "BOSS", "B 汇总岗位", "B 公司");
        UUID matchB = seedMatch(userBId, jobB, "SUCCEEDED", "APPLY", null, "B 结论", "[]", "[]");
        createTask(userB, matchB, jobB, "p6-sum-b");

        HttpResponse<String> summary = userA.get("/api/delivery/tasks/summary");
        assertThat(summary.statusCode()).as(summary.body()).isEqualTo(200);
        assertThat(json(summary).at("/data/waitingConfirm").asLong()).isEqualTo(1);
        assertThat(json(summary).at("/data/confirmed").asLong()).isEqualTo(1);
        assertThat(json(summary).at("/data/skipped").asLong()).isEqualTo(1);
        assertThat(json(summary).at("/data/pausedNeedUser").asLong()).isEqualTo(1);
        assertThat(json(summary).at("/data/total").asLong()).isEqualTo(4);

        // Filters match the list endpoint; pagination is irrelevant.
        assertThat(json(userA.get("/api/delivery/tasks/summary?platform=BOSS")).at("/data/total").asLong())
                .isEqualTo(3);
        assertThat(json(userA.get("/api/delivery/tasks/summary?recommendation=SKIP")).at("/data/total").asLong())
                .isEqualTo(1);
        assertThat(json(userA.get("/api/delivery/tasks/summary?page=9&size=1")).at("/data/total").asLong())
                .isEqualTo(4);

        // B only ever counts its own task, never A's rows.
        assertThat(json(userB.get("/api/delivery/tasks/summary")).at("/data/total").asLong()).isEqualTo(1);
        assertThat(json(userB.get("/api/delivery/tasks/summary")).at("/data/waitingConfirm").asLong()).isEqualTo(1);
        assertThat(json(userB.get("/api/delivery/tasks/summary")).at("/data/confirmed").asLong()).isZero();

        // The tasks themselves stay untouched by the summary reads.
        assertThat(json(userA.get("/api/delivery/tasks/" + task1)).at("/data/status").asText())
                .isEqualTo("WAITING_CONFIRM");
    }

    // ---- 5. Execution disabled by default (P6 master switch) ----

    @Test
    void executionDisabledByDefaultAnswersSafeErrorsOnEveryPluginEndpoint() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "p6-exec@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId);

        UUID jobId = seedJob(userId, "BOSS", "开关岗位", "示例科技");
        UUID matchId = seedMatch(userId, jobId, "SUCCEEDED", "APPLY", null, "开关结论", "[]", "[]");
        String taskId = createTask(user, matchId, jobId, "p6-exec-create");
        assertThat(user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true}", user.csrf(),
                Map.of("Idempotency-Key", "p6-exec-confirm")).statusCode()).isEqualTo(200);

        // Confirmed and nothing else: the P6 API never leaks execution states.
        assertThat(json(user.get("/api/delivery/tasks/" + taskId)).at("/data/status").asText())
                .isEqualTo("CONFIRMED");

        // Web plugin endpoints answer a safe 503 for a valid plugin token.
        PluginClient plugin = new PluginClient(device.token());
        HttpResponse<String> pending = plugin.get("/api/plugin/tasks/pending");
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(503);
        assertThat(json(pending).at("/error/code").asText()).isEqualTo("EXECUTION_DISABLED");
        HttpResponse<String> start = plugin.post("/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":2,\"executionId\":\"exec-p6-000001\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "p6-exec-start"));
        assertThat(start.statusCode()).isEqualTo(503);
        assertThat(json(start).at("/error/code").asText()).isEqualTo("EXECUTION_DISABLED");

        // Service-level: every mutating entry point refuses before validation
        // or state changes.
        UUID anyTask = UUID.fromString(taskId);
        assertExecutionDisabled(() -> deliveryService.pending(
                userId, device.deviceId(), List.of("BOSS"), 10, null));
        assertExecutionDisabled(() -> deliveryService.start(
                userId, device.deviceId(), anyTask,
                new DeliveryModels.StartRequest(2, "exec-p6-000002", "1.2.0", null), "p6-exec-start-2"));
        assertExecutionDisabled(() -> deliveryService.success(
                userId, device.deviceId(), anyTask,
                new DeliveryModels.SuccessRequest(null, "exec-p6-000002", 2, null, "DELIVERED",
                        Map.of("pageState", "SUCCESS_NOTICE")), "p6-exec-success"));
        assertExecutionDisabled(() -> deliveryService.fail(
                userId, device.deviceId(), anyTask,
                new DeliveryModels.FailRequest(null, "exec-p6-000002", 2, null, "NETWORK_ERROR", "无", true),
                "p6-exec-fail"));
        assertExecutionDisabled(() -> deliveryService.pause(
                userId, device.deviceId(), anyTask,
                new DeliveryModels.PauseRequest(null, "exec-p6-000002", 2, null, "CAPTCHA_REQUIRED", null),
                "p6-exec-pause"));
        assertExecutionDisabled(() -> deliveryService.batchPause(userId, device.deviceId(), null));

        // The task row is untouched by the refused calls.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleString(stmt.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + taskId + "'"))).isEqualTo("CONFIRMED");
        }
    }

    private void assertExecutionDisabled(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("EXECUTION_DISABLED");
                });
    }

    // ---- helpers ----

    private record PluginSession(UUID deviceId, String token) {
    }

    private UUID register(BrowserSession browser, String email) throws Exception {
        HttpResponse<String> register = browser.post(
                "/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"StrongPassword!2026\",\"acceptTerms\":true}",
                browser.csrf()
        );
        assertThat(register.statusCode()).as(register.body()).isEqualTo(201);
        return UUID.fromString(json(register).at("/data/user/id").asText());
    }

    private PluginSession bindDevice(BrowserSession browser, UUID userId) throws Exception {
        HttpResponse<String> bindCode = browser.post(
                "/api/plugin/bind-code", "", browser.csrf(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(bindCode.statusCode()).as(bindCode.body()).isEqualTo(201);
        String code = json(bindCode).at("/data/bindCode").asText();
        String installationId = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> bound = new PluginClient(null).post("/api/plugin/bind", """
                {"bindCode":"%s","installationId":"%s","deviceName":"P6设备",
                 "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                 "capabilities":["BOSS"]}
                """.formatted(code, installationId));
        assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);
        return new PluginSession(
                UUID.fromString(json(bound).at("/data/device/id").asText()),
                json(bound).at("/data/token/value").asText()
        );
    }

    private String createTask(BrowserSession user, UUID matchId, UUID jobId, String key) throws Exception {
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobMatchId\":\"" + matchId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).at("/data/id").asText();
    }

    private UUID seedJob(UUID userId, String platform, String title, String company) throws Exception {
        UUID jobId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.prepareStatement("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                """)) {
            stmt.setObject(1, jobId);
            stmt.setObject(2, userId);
            stmt.setString(3, platform);
            stmt.setString(4, hex(UUID.randomUUID().toString() + UUID.randomUUID()));
            stmt.setString(5, title);
            stmt.setString(6, company);
            stmt.setString(7, "BOSS".equals(platform)
                    ? "https://www.zhipin.com/job_detail/p6.html"
                    : "https://sou.zhaopin.com/jobs/jobdetail/p6");
            stmt.executeUpdate();
        }
        return jobId;
    }

    /** One current resume + one preference per user (unique indexes enforce this). */
    private static final Map<UUID, UUID> RESUME_BY_USER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, UUID> PREFERENCE_BY_USER = new java.util.concurrent.ConcurrentHashMap<>();

    private UUID seedMatch(
            UUID userId, UUID jobId, String status, String decision, String greeting,
            String summary, String strengthsJson, String risksJson
    ) throws Exception {
        UUID resumeId = RESUME_BY_USER.computeIfAbsent(userId, id -> {
            try {
                return seedResume(userId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        UUID preferenceId = PREFERENCE_BY_USER.computeIfAbsent(userId, id -> {
            try {
                return seedPreference(userId);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        UUID matchId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        decision, greeting, summary, strengths, risks, input_fingerprint, completed_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', '%s', %s, %s, %s, %s, %s, '%s', %s
                    )
                    """.formatted(matchId, userId, jobId, resumeId, preferenceId, status,
                    decision == null ? "NULL" : "'" + decision + "'",
                    greeting == null ? "NULL" : "'" + greeting + "'",
                    summary == null ? "NULL" : "'" + summary + "'",
                    strengthsJson == null ? "'[]'::jsonb" : "'" + strengthsJson + "'::jsonb",
                    risksJson == null ? "'[]'::jsonb" : "'" + risksJson + "'::jsonb",
                    hex(UUID.randomUUID().toString() + UUID.randomUUID()),
                    status.equals("PENDING") ? "NULL" : "now()"));
        }
        return matchId;
    }

    private UUID seedResume(UUID userId) throws Exception {
        UUID resumeId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.pdf', 'objects/%s', 'application/pdf', 100,
                        '%s', '%s', 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, userId, resumeId, hex(resumeId.toString()),
                    hex(UUID.randomUUID().toString())));
        }
        return resumeId;
    }

    private UUID seedPreference(UUID userId) throws Exception {
        UUID preferenceId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles)
                    VALUES ('%s', '%s', 1, '["Java工程师"]'::jsonb)
                    """.formatted(preferenceId, userId));
        }
        return preferenceId;
    }

    /** Seeds a canonical PAUSED_NEED_USER task for a SUCCEEDED match. */
    private void seedPausedTask(UUID userId, UUID jobId, UUID matchId) throws Exception {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, job_match_id, status,
                        confirmed_at, confirmed_by, last_error_code, last_error_message,
                        last_error_retryable, idempotency_key_hash, idempotency_payload_hash
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'PAUSED_NEED_USER', now(), '%s',
                        'CAPTCHA_REQUIRED', '需要人工验证', false, '%s', '%s'
                    )
                    """.formatted(UUID.randomUUID(), userId, jobId, matchId, userId,
                    hex(UUID.randomUUID().toString() + UUID.randomUUID()),
                    hex(UUID.randomUUID().toString() + UUID.randomUUID())));
        }
    }

    private static String hex(String value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            result.append(String.format("%02x", b));
        }
        return result.toString().substring(0, Math.min(64, result.length()));
    }

    private Connection ownerConnection() throws Exception {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        connection.setAutoCommit(true);
        return connection;
    }

    private static long singleLong(java.sql.ResultSet resultSet) throws Exception {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(java.sql.ResultSet resultSet) throws Exception {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static String suffix() {
        return Long.toHexString(System.nanoTime());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private final class BrowserSession {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String csrf() throws Exception {
            return json(get("/api/auth/csrf")).at("/data/csrfToken").asText();
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        HttpResponse<String> post(String path, String body, String csrfToken) throws Exception {
            return post(path, body, csrfToken, Map.of());
        }

        HttpResponse<String> post(String path, String body, String csrfToken, Map<String, String> headers)
                throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(body));
            if (csrfToken != null) {
                builder.header("X-CSRF-TOKEN", csrfToken);
            }
            headers.forEach(builder::header);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> put(String path, String body, String csrfToken) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", csrfToken == null ? "" : csrfToken)
                    .method("PUT", HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }

    private final class PluginClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        private final String token;

        PluginClient(String token) {
            this.token = token;
        }

        HttpResponse<String> get(String path) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return post(path, body, Map.of());
        }

        HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(body));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            headers.forEach(builder::header);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }
}
