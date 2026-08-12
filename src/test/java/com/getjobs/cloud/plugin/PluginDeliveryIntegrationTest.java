package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.CloudApplication;
import com.getjobs.cloud.delivery.DeliveryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level integration tests for the plugin binding, device/token security
 * and the Web + plugin delivery task flows. Runs against Testcontainers
 * PostgreSQL (with the real RLS-gated app role) and Redis.
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
                "app.auth.hash-pepper=plugin-delivery-integration-pepper-32-bytes",
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
class PluginDeliveryIntegrationTest {
    private static final String REDIS_PASSWORD = "integration_plugin_delivery_redis";
    private static final String APP_PASSWORD = "integration-app-password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-plugin-delivery-" + UUID.randomUUID()
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

    @Autowired
    PluginBindCodeService bindCodeService;

    @Autowired
    com.getjobs.cloud.auth.SecurityFingerprintService fingerprints;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    // ---- 1. Binding, token issuance and identity separation ----

    @Test
    void bindFlowIssuesSingleUseTokenAndSeparatesPluginFromWeb() throws Exception {
        BrowserSession browser = new BrowserSession();
        UUID userId = register(browser, "bind@" + suffix() + ".example.com");

        // Bind code: idempotent per key, rotates per new key
        HttpResponse<String> bindCode = browser.post(
                "/api/plugin/bind-code", "", browser.csrf(), Map.of("Idempotency-Key", "bind-code-key-1"));
        assertThat(bindCode.statusCode()).isEqualTo(201);
        String code = json(bindCode).at("/data/bindCode").asText();
        assertThat(code).matches("[A-Z0-9]{5}-[A-Z0-9]{5}");
        assertThat(json(bindCode).at("/data/expiresInSeconds").asLong()).isEqualTo(300);
        assertThat(bindCode.body()).doesNotContain(userId.toString());
        HttpResponse<String> replay = browser.post(
                "/api/plugin/bind-code", "", browser.csrf(), Map.of("Idempotency-Key", "bind-code-key-1"));
        assertThat(json(replay).at("/data/bindCode").asText()).isEqualTo(code);

        // Anonymous bind with the one-time code
        String installationId = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String bindBody = """
                {"bindCode":"%s","installationId":"%s","deviceName":"我的 Chrome",
                 "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                 "capabilities":["BOSS","ZHILIAN"]}
                """.formatted(code, installationId);
        HttpResponse<String> bound = new PluginClient().post("/api/plugin/bind", bindBody);
        assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);
        String token = json(bound).at("/data/token/value").asText();
        assertThat(token).startsWith("ajp_plg_");
        UUID deviceId = UUID.fromString(json(bound).at("/data/device/id").asText());
        assertThat(json(bound).at("/data/device/status").asText()).isEqualTo("ACTIVE");
        // The one-shot plaintext token response is explicitly non-cacheable.
        assertThat(bound.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(bound.headers().firstValue("Pragma")).contains("no-cache");
        // The audit ip_hash is the keyed HMAC fingerprint of the remote address
        // (test pepper), never a dictionary-reversible plain IP SHA-256.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var auditRows = stmt.executeQuery(
                    "SELECT ip_hash FROM app.audit_logs WHERE user_id='" + userId
                            + "' AND action='PLUGIN_DEVICE_BOUND'");
            assertThat(auditRows.next()).isTrue();
            String ipHash = auditRows.getString(1);
            assertThat(ipHash).isEqualTo(fingerprints.hash("127.0.0.1"));
            assertThat(ipHash).isNotEqualTo(sha256Hex("127.0.0.1"));
            assertThat(ipHash).doesNotContain("127.0.0.1");
        }

        // The code is single-use
        HttpResponse<String> reuse = new PluginClient().post("/api/plugin/bind", bindBody);
        assertThat(reuse.statusCode()).isEqualTo(401);
        assertThat(json(reuse).at("/error/code").asText()).isEqualTo("BIND_CODE_INVALID");

        // Token works for /api/plugin/me with minimal user fields
        PluginClient plugin = new PluginClient(token);
        HttpResponse<String> me = plugin.get("/api/plugin/me");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(json(me).at("/data/user/id").asText()).isEqualTo(userId.toString());
        assertThat(json(me).at("/data/device/id").asText()).isEqualTo(deviceId.toString());
        assertThat(json(me).at("/data/token/scopes").toString()).contains("tasks:read");
        assertThat(me.body()).doesNotContain("@example.com");

        // Identity separation: plugin token cannot reach Web endpoints...
        assertThat(plugin.get("/api/resumes").statusCode()).isEqualTo(401);
        assertThat(plugin.get("/api/preferences").statusCode()).isEqualTo(401);
        // The plugin principal holds scope authorities only, so the Web chain denies it.
        assertThat(plugin.post("/api/plugin/bind-code", "", null).statusCode()).isEqualTo(403);
        // ...and a Web session cannot reach plugin endpoints
        assertThat(browser.get("/api/plugin/me").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/plugin/tasks/pending").statusCode()).isEqualTo(401);
        // Missing/garbage tokens never disclose existence
        assertThat(new PluginClient().get("/api/plugin/me").statusCode()).isEqualTo(401);
        assertThat(new PluginClient("ajp_plg_forged-token-value").get("/api/plugin/me").statusCode()).isEqualTo(401);

        // The database only stores the token hash, never the plaintext
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var rows = stmt.executeQuery(
                    "SELECT token_hash, token_prefix FROM app.plugin_tokens WHERE user_id='" + userId + "'");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).matches("[0-9a-f]{64}").doesNotContain(token);
            assertThat(token).startsWith(rows.getString(2));
        }

        // Web device list shows the device; revocation kills the token instantly
        HttpResponse<String> devices = browser.get("/api/plugin/devices");
        assertThat(devices.statusCode()).isEqualTo(200);
        assertThat(json(devices).at("/data").toString()).contains(deviceId.toString());
        HttpResponse<String> revoked = browser.post(
                "/api/plugin/devices/" + deviceId + "/revoke", "{\"reason\":\"测试撤销\"}", browser.csrf());
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(new PluginClient(token).get("/api/plugin/me").statusCode()).isEqualTo(403);
        assertThat(json(new PluginClient(token).get("/api/plugin/me")).at("/error/code").asText())
                .isEqualTo("DEVICE_REVOKED");

        // Revoking another user's device is a unified 404
        BrowserSession other = new BrowserSession();
        UUID otherUser = register(other, "other@" + suffix() + ".example.com");
        assertThat(browser.post(
                "/api/plugin/devices/" + UUID.randomUUID() + "/revoke", "{}", browser.csrf()
        ).statusCode()).isEqualTo(404);
        assertThat(other.post(
                "/api/plugin/devices/" + deviceId + "/revoke", "{}", other.csrf()
        ).statusCode()).isEqualTo(404);
    }

    // ---- 2. Web delivery create/greeting/confirm + plugin execute ----

    @Test
    void webDeliveryCreateGreetingConfirmAndPluginExecution() throws Exception {
        BrowserSession userA = new BrowserSession();
        BrowserSession userB = new BrowserSession();
        UUID userAId = register(userA, "delivery-a@" + suffix() + ".example.com");
        register(userB, "delivery-b@" + suffix() + ".example.com");

        UUID jobId = seedApplicableJob(userAId, "BOSS", "APPLY", "AI 推荐的招呼语");
        PluginSession device = bindDevice(userA, userAId, "执行设备", "[\"BOSS\"]");
        UUID deviceId = device.deviceId();

        // Create: default PENDING_CONFIRMATION with the match greeting
        HttpResponse<String> created = userA.post(
                "/api/delivery/tasks",
                "{\"jobPostId\":\"" + jobId + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "create-task-1"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID taskId = UUID.fromString(json(created).at("/data/id").asText());
        assertThat(json(created).at("/data/status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(json(created).at("/data/greeting").asText()).isEqualTo("AI 推荐的招呼语");
        assertThat(json(created).at("/data/version").asInt()).isEqualTo(1);

        // Same key replays; a second create for the same job is rejected
        HttpResponse<String> replayCreate = userA.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "create-task-1"));
        assertThat(json(replayCreate).at("/data/id").asText()).isEqualTo(taskId.toString());
        HttpResponse<String> duplicate = userA.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "create-task-2"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).at("/error/code").asText()).isEqualTo("DUPLICATE_ACTIVE_TASK");

        // Cross-user access is a unified 404
        assertThat(userB.get("/api/delivery/tasks/" + taskId).statusCode()).isEqualTo(404);
        assertThat(userB.put("/api/delivery/tasks/" + taskId + "/greeting",
                "{\"version\":1,\"greeting\":\"越权\"}", userB.csrf()).statusCode()).isEqualTo(404);
        assertThat(userB.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true}", userB.csrf(),
                Map.of("Idempotency-Key", "b-confirm")).statusCode()).isEqualTo(404);

        // Greeting edit with optimistic version
        HttpResponse<String> greeting = userA.put(
                "/api/delivery/tasks/" + taskId + "/greeting",
                "{\"version\":1,\"greeting\":\"您好，期待与您沟通\"}", userA.csrf());
        assertThat(greeting.statusCode()).as(greeting.body()).isEqualTo(200);
        assertThat(json(greeting).at("/data/version").asInt()).isEqualTo(2);
        HttpResponse<String> staleGreeting = userA.put(
                "/api/delivery/tasks/" + taskId + "/greeting",
                "{\"version\":1,\"greeting\":\"旧版本\"}", userA.csrf());
        assertThat(staleGreeting.statusCode()).isEqualTo(409);
        assertThat(json(staleGreeting).at("/error/code").asText()).isEqualTo("RESOURCE_VERSION_CONFLICT");

        // Confirm with the bound device
        HttpResponse<String> confirmed = userA.post(
                "/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":2,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "confirm-key-1"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(confirmed).at("/data/confirmationVersion").asInt()).isEqualTo(1);
        int confirmedVersion = json(confirmed).at("/data/version").asInt();

        // Plugin pending: visible only to the assigned device
        PluginClient plugin = new PluginClient(device.token());
        HttpResponse<String> pending = plugin.get("/api/plugin/tasks/pending");
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        assertThat(json(pending).at("/data/items").toString()).contains(taskId.toString());
        assertThat(json(pending).at("/data/items/0/greeting").asText()).isEqualTo("您好，期待与您沟通");
        assertThat(json(pending).at("/data/items/0/confirmationVersion").asInt()).isEqualTo(1);
        assertThat(json(pending).at("/data/pollAfterSeconds").asInt()).isPositive();

        // Atomic start
        HttpResponse<String> started = plugin.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + confirmedVersion + ",\"executionId\":\"exec-00000001\","
                        + "\"extensionVersion\":\"1.2.0\",\"pageUrl\":\"https://www.zhipin.com/job_detail/test.html\"}",
                Map.of("Idempotency-Key", "start-key-1"));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json(started).at("/data/status").asText()).isEqualTo("EXECUTING");
        String leaseId = json(started).at("/data/leaseId").asText();
        int executingVersion = json(started).at("/data/version").asInt();
        assertThat(json(started).at("/data/task/greeting").asText()).isEqualTo("您好，期待与您沟通");

        // Same execution replays with the same lease
        HttpResponse<String> replayStart = plugin.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + confirmedVersion + ",\"executionId\":\"exec-00000001\","
                        + "\"extensionVersion\":\"1.2.0\",\"pageUrl\":\"https://www.zhipin.com/job_detail/test.html\"}",
                Map.of("Idempotency-Key", "start-key-1"));
        assertThat(json(replayStart).at("/data/leaseId").asText()).isEqualTo(leaseId);

        // Untrusted page URLs are rejected before any state change
        HttpResponse<String> badUrl = plugin.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + executingVersion + ",\"executionId\":\"exec-00000002\","
                        + "\"extensionVersion\":\"1.2.0\",\"pageUrl\":\"https://evil.example.com/job\"}",
                Map.of("Idempotency-Key", "start-key-2"));
        assertThat(badUrl.statusCode()).isEqualTo(422);
        assertThat(json(badUrl).at("/error/code").asText()).isEqualTo("UNTRUSTED_JOB_URL");

        // Success with evidence; terminal and replay-safe
        HttpResponse<String> success = plugin.post(
                "/api/plugin/tasks/" + taskId + "/success",
                "{\"leaseId\":\"" + leaseId + "\",\"executionId\":\"exec-00000001\",\"version\":" + executingVersion
                        + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\","
                        + "\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\"}}",
                Map.of("Idempotency-Key", "success-key-1"));
        assertThat(success.statusCode()).as(success.body()).isEqualTo(200);
        assertThat(json(success).at("/data/status").asText()).isEqualTo("SUCCEEDED");
        int succeededVersion = json(success).at("/data/version").asInt();
        HttpResponse<String> successReplay = plugin.post(
                "/api/plugin/tasks/" + taskId + "/success",
                "{\"leaseId\":\"" + leaseId + "\",\"executionId\":\"exec-00000001\",\"version\":" + executingVersion
                        + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\","
                        + "\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\"}}",
                Map.of("Idempotency-Key", "success-key-1"));
        assertThat(successReplay.statusCode()).isEqualTo(200);
        // Terminal state is protected from later failures
        HttpResponse<String> lateFail = plugin.post(
                "/api/plugin/tasks/" + taskId + "/fail",
                "{\"leaseId\":\"" + leaseId + "\",\"executionId\":\"exec-00000001\",\"version\":" + succeededVersion
                        + ",\"failedAt\":\"2026-08-13T10:01:00Z\",\"errorCode\":\"NETWORK_ERROR\","
                        + "\"message\":\"晚到的失败\",\"retryable\":true}",
                Map.of("Idempotency-Key", "late-fail-key"));
        assertThat(lateFail.statusCode()).isEqualTo(409);

        // Timeline carries the full append-only history
        HttpResponse<String> detail = userA.get("/api/delivery/tasks/" + taskId);
        assertThat(detail.statusCode()).isEqualTo(200);
        String events = json(detail).at("/data/events").toString();
        for (String event : new String[]{"CREATED", "GREETING_UPDATED", "CONFIRMED", "STARTED", "SUCCEEDED"}) {
            assertThat(events).contains("\"eventType\":\"" + event + "\"");
        }
        // Web list rows carry job, match, device and last event summaries
        HttpResponse<String> list = userA.get("/api/delivery/tasks?status=SUCCEEDED");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).at("/data/items").toString()).contains(taskId.toString());
        assertThat(json(list).at("/data/items/0/job/platform").asText()).isEqualTo("BOSS");
        assertThat(json(list).at("/data/items/0/device/deviceName").asText()).isEqualTo("执行设备");
        assertThat(json(list).at("/data/items/0/lastEvent/eventType").asText()).isEqualTo("SUCCEEDED");

        // Job pool integration shows the real delivery status
        HttpResponse<String> jobDetail = userA.get("/api/jobs/" + jobId);
        assertThat(json(jobDetail).at("/data/deliveryTask/status").asText()).isEqualTo("SUCCEEDED");
        HttpResponse<String> jobList = userA.get("/api/jobs?platform=BOSS");
        assertThat(json(jobList).at("/data/items/0/deliveryTaskStatus/status").asText()).isEqualTo("SUCCEEDED");

        // Audit trail exists for the key delivery actions
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var count = stmt.executeQuery("SELECT count(*) FROM app.audit_logs WHERE user_id='" + userAId
                    + "' AND action IN ('DELIVERY_TASK_CREATED','DELIVERY_TASK_CONFIRMED',"
                    + "'DELIVERY_GREETING_UPDATED','PLUGIN_TASK_STARTED','PLUGIN_TASK_SUCCEEDED')");
            assertThat(count.next()).isTrue();
            assertThat(count.getLong(1)).isGreaterThanOrEqualTo(5);
        }
    }

    // ---- 3. Auto-created tasks, skip, pause, fail and lease recovery ----

    @Test
    void autoApplySkipPauseFailAndLeaseRecoveryFlows() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "flows@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "流程设备", "[\"BOSS\",\"ZHILIAN\"]");
        UUID deviceId = device.deviceId();
        PluginClient plugin = new PluginClient(device.token());

        // Auto-create: completing an APPLY match triggers the DB trigger
        UUID autoJob = seedJob(userId, "BOSS");
        UUID matchId = seedMatch(userId, autoJob, "PROCESSING", null, null);
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.job_matches SET status='SUCCEEDED', decision='APPLY', "
                    + "greeting='自动任务问候', completed_at=now() WHERE id='" + matchId + "'");
        }
        HttpResponse<String> tasks = user.get("/api/delivery/tasks");
        assertThat(json(tasks).at("/data/items").toString()).contains("\"status\":\"PENDING_CONFIRMATION\"");
        String autoTaskId = json(tasks).at("/data/items/0/id").asText();
        assertThat(json(tasks).at("/data/items/0/greeting").asText()).isEqualTo("自动任务问候");

        // Skip the un-executed auto task; a second skip is invalid
        HttpResponse<String> skipped = user.post(
                "/api/delivery/tasks/" + autoTaskId + "/skip",
                "{\"version\":1,\"reason\":\"NOT_INTERESTED\"}",
                user.csrf(), Map.of("Idempotency-Key", "skip-auto"));
        assertThat(skipped.statusCode()).as(skipped.body()).isEqualTo(200);
        assertThat(json(skipped).at("/data/status").asText()).isEqualTo("SKIPPED");
        HttpResponse<String> skipAgain = user.post(
                "/api/delivery/tasks/" + autoTaskId + "/skip",
                "{\"version\":" + json(skipped).at("/data/version").asInt() + ",\"reason\":\"NOT_INTERESTED\"}",
                user.csrf(), Map.of("Idempotency-Key", "skip-auto-2"));
        assertThat(skipAgain.statusCode()).isEqualTo(409);

        // ZHILIAN: greeting is unsupported and stays null
        UUID zhilianJob = seedApplicableJob(userId, "ZHILIAN", "REVIEW", null);
        HttpResponse<String> zhilianTask = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + zhilianJob + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "zhilian-create"));
        assertThat(zhilianTask.statusCode()).as(zhilianTask.body()).isEqualTo(201);
        UUID zhilianTaskId = UUID.fromString(json(zhilianTask).at("/data/id").asText());
        assertThat(json(zhilianTask).at("/data/greeting").isNull()).isTrue();
        HttpResponse<String> zhilianGreeting = user.put(
                "/api/delivery/tasks/" + zhilianTaskId + "/greeting",
                "{\"version\":1,\"greeting\":\"智联不需要\"}", user.csrf());
        assertThat(zhilianGreeting.statusCode()).isEqualTo(422);
        assertThat(json(zhilianGreeting).at("/error/code").asText()).isEqualTo("GREETING_UNSUPPORTED");

        // SKIP decisions cannot be turned into tasks
        UUID skipJob = seedApplicableJob(userId, "BOSS", "SKIP", null);
        HttpResponse<String> skipTask = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + skipJob + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "skip-job-create"));
        assertThat(skipTask.statusCode()).isEqualTo(422);
        assertThat(json(skipTask).at("/error/code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");

        // Pause -> user re-confirms -> fail with server-side retryability
        UUID flowJob = seedApplicableJob(userId, "BOSS", "APPLY", "流程问候");
        HttpResponse<String> flowTask = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + flowJob + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "flow-create"));
        UUID flowTaskId = UUID.fromString(json(flowTask).at("/data/id").asText());
        assertThat(user.post("/api/delivery/tasks/" + flowTaskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "flow-confirm-1")).statusCode()).isEqualTo(200);
        HttpResponse<String> flowStart = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/start",
                "{\"version\":2,\"executionId\":\"exec-flow-0001\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "flow-start-1"));
        String flowLease = json(flowStart).at("/data/leaseId").asText();
        HttpResponse<String> paused = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/pause",
                "{\"leaseId\":\"" + flowLease + "\",\"executionId\":\"exec-flow-0001\",\"version\":3,"
                        + "\"pausedAt\":\"2026-08-13T10:00:00Z\",\"reason\":\"CAPTCHA_REQUIRED\","
                        + "\"message\":\"需要人工验证\"}",
                Map.of("Idempotency-Key", "flow-pause-1"));
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        assertThat(json(paused).at("/data/status").asText()).isEqualTo("PAUSED");
        assertThat(json(paused).at("/data/userActionRequired").asBoolean()).isTrue();
        assertThat(json(paused).at("/data/leaseReleased").asBoolean()).isTrue();
        // A paused task cannot be started without a fresh confirmation
        HttpResponse<String> pausedStart = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/start",
                "{\"version\":4,\"executionId\":\"exec-flow-0002\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "flow-start-2"));
        assertThat(pausedStart.statusCode()).isEqualTo(409);
        // Re-confirm from PAUSED
        assertThat(user.post("/api/delivery/tasks/" + flowTaskId + "/confirm",
                "{\"version\":4,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "flow-confirm-2")).statusCode()).isEqualTo(200);
        // Fail with BUTTON_NOT_FOUND -> server marks retryable
        HttpResponse<String> flowStart2 = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/start",
                "{\"version\":5,\"executionId\":\"exec-flow-0003\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "flow-start-3"));
        String flowLease2 = json(flowStart2).at("/data/leaseId").asText();
        HttpResponse<String> failed = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/fail",
                "{\"leaseId\":\"" + flowLease2 + "\",\"executionId\":\"exec-flow-0003\",\"version\":6,"
                        + "\"failedAt\":\"2026-08-13T10:02:00Z\",\"errorCode\":\"BUTTON_NOT_FOUND\","
                        + "\"message\":\"按钮不存在\",\"retryable\":false}",
                Map.of("Idempotency-Key", "flow-fail-1"));
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(200);
        assertThat(json(failed).at("/data/status").asText()).isEqualTo("FAILED");
        assertThat(json(failed).at("/data/retryable").asBoolean()).isTrue();
        // This is the second plugin attempt: start#1 paused, start#2 failed
        assertThat(json(failed).at("/data/attemptCount").asInt()).isEqualTo(2);
        // Retryable FAILED can be re-confirmed; JOB_CLOSED forces non-retryable
        assertThat(user.post("/api/delivery/tasks/" + flowTaskId + "/confirm",
                "{\"version\":7,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "flow-confirm-3")).statusCode()).isEqualTo(200);
        HttpResponse<String> flowStart3 = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/start",
                "{\"version\":8,\"executionId\":\"exec-flow-0004\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "flow-start-4"));
        String flowLease3 = json(flowStart3).at("/data/leaseId").asText();
        HttpResponse<String> closed = plugin.post(
                "/api/plugin/tasks/" + flowTaskId + "/fail",
                "{\"leaseId\":\"" + flowLease3 + "\",\"executionId\":\"exec-flow-0004\",\"version\":9,"
                        + "\"failedAt\":\"2026-08-13T10:03:00Z\",\"errorCode\":\"JOB_CLOSED\","
                        + "\"message\":\"岗位已关闭\",\"retryable\":true}",
                Map.of("Idempotency-Key", "flow-fail-2"));
        assertThat(json(closed).at("/data/retryable").asBoolean()).isFalse();
        // Non-retryable FAILED cannot be re-confirmed
        HttpResponse<String> closedConfirm = user.post(
                "/api/delivery/tasks/" + flowTaskId + "/confirm",
                "{\"version\":10,\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "flow-confirm-4"));
        assertThat(closedConfirm.statusCode()).isEqualTo(409);

        // Lease expiry: the sweep recovers EXECUTING back to CONFIRMED
        UUID leaseJob = seedApplicableJob(userId, "BOSS", "APPLY", "租约问候");
        HttpResponse<String> leaseTask = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + leaseJob + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "lease-create"));
        UUID leaseTaskId = UUID.fromString(json(leaseTask).at("/data/id").asText());
        assertThat(user.post("/api/delivery/tasks/" + leaseTaskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "lease-confirm")).statusCode()).isEqualTo(200);
        assertThat(plugin.post(
                "/api/plugin/tasks/" + leaseTaskId + "/start",
                "{\"version\":2,\"executionId\":\"exec-lease-001\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "lease-start")).statusCode()).isEqualTo(200);
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.delivery_tasks SET lease_expires_at = now() - interval '1 second' "
                    + "WHERE id='" + leaseTaskId + "'");
        }
        StringBuilder diagnostic = new StringBuilder();
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var rows = stmt.executeQuery("SELECT id, status, execution_id, lease_expires_at, now() " +
                    "FROM app.delivery_tasks WHERE status IN ('LEASED','EXECUTING')");
            while (rows.next()) {
                diagnostic.append("id=").append(rows.getString(1)).append(" status=").append(rows.getString(2))
                        .append(" exec=").append(rows.getString(3)).append(" lease=").append(rows.getString(4))
                        .append(" now=").append(rows.getString(5)).append("; ");
            }
        }
        int recovered = deliveryService.recoverExpiredLeases();
        if (recovered != 1) {
            throw new AssertionError("recovered=" + recovered + " before=" + diagnostic);
        }
        HttpResponse<String> leaseDetail = user.get("/api/delivery/tasks/" + leaseTaskId);
        assertThat(json(leaseDetail).at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(leaseDetail).at("/data/events").toString()).contains("LEASE_EXPIRED");
    }

    // ---- 4. Pending list isolation and minimal fields ----

    @Test
    void pendingListOnlyReturnsConfirmedTasksForTheTokenDevice() throws Exception {
        BrowserSession userA = new BrowserSession();
        BrowserSession userB = new BrowserSession();
        UUID userAId = register(userA, "pending-a@" + suffix() + ".example.com");
        UUID userBId = register(userB, "pending-b@" + suffix() + ".example.com");

        PluginSession deviceA = bindDevice(userA, userAId, "A设备", "[\"BOSS\"]");
        PluginSession deviceB = bindDevice(userA, userAId, "B设备", "[\"ZHILIAN\"]");
        PluginSession otherDevice = bindDevice(userB, userBId, "他人设备", "[\"BOSS\",\"ZHILIAN\"]");

        UUID jobA = seedApplicableJob(userAId, "BOSS", "APPLY", "A任务");
        UUID jobB = seedApplicableJob(userBId, "BOSS", "APPLY", "B任务");
        UUID jobPending = seedApplicableJob(userAId, "BOSS", "APPLY", "待确认任务");

        HttpResponse<String> taskA = userA.post("/api/delivery/tasks", "{\"jobPostId\":\"" + jobA + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "pending-a"));
        UUID taskAId = UUID.fromString(json(taskA).at("/data/id").asText());
        HttpResponse<String> taskB = userB.post("/api/delivery/tasks", "{\"jobPostId\":\"" + jobB + "\"}",
                userB.csrf(), Map.of("Idempotency-Key", "pending-b"));
        UUID taskBId = UUID.fromString(json(taskB).at("/data/id").asText());
        userA.post("/api/delivery/tasks", "{\"jobPostId\":\"" + jobPending + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "pending-unconfirmed"));

        // Unconfirmed tasks never appear for the plugin
        PluginClient pluginA = new PluginClient(deviceA.token());
        assertThat(json(pluginA.get("/api/plugin/tasks/pending")).at("/data/items").toString())
                .doesNotContain(jobPending.toString());

        // Confirm A's task to device A; B's task stays with user B
        assertThat(userA.post("/api/delivery/tasks/" + taskAId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceA.deviceId() + "\"}",
                userA.csrf(), Map.of("Idempotency-Key", "pending-confirm-a")).statusCode()).isEqualTo(200);
        assertThat(userB.post("/api/delivery/tasks/" + taskBId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + otherDevice.deviceId() + "\"}",
                userB.csrf(), Map.of("Idempotency-Key", "pending-confirm-b")).statusCode()).isEqualTo(200);

        String itemsA = json(pluginA.get("/api/plugin/tasks/pending")).at("/data/items").toString();
        assertThat(itemsA).contains(taskAId.toString()).doesNotContain(taskBId.toString());

        // A's ZHILIAN-only device cannot see BOSS tasks; B's device cannot see A's tasks
        assertThat(json(new PluginClient(deviceB.token()).get("/api/plugin/tasks/pending"))
                .at("/data/items").toString()).doesNotContain(taskAId.toString());
        assertThat(json(new PluginClient(otherDevice.token()).get("/api/plugin/tasks/pending"))
                .at("/data/items").toString()).doesNotContain(taskAId.toString());

        // Minimal fields only: no resumes, no match data, no cookies
        String pendingJson = pluginA.get("/api/plugin/tasks/pending").body();
        assertThat(pendingJson).doesNotContain("resume", "preference", "match", "cookie", "email");
        // Platform filter works on top of device capabilities
        assertThat(json(pluginA.get("/api/plugin/tasks/pending?platform=ZHILIAN")).at("/data/items").size())
                .isZero();
        assertThat(json(pluginA.get("/api/plugin/tasks/pending?platform=BOSS")).at("/data/items").size())
                .isEqualTo(1);
    }

    // ---- 5. Bind-code atomicity, null-device confirm, scopes, idempotency ----

    @Test
    void concurrentBindCodeRequestsWithSameKeyAllObserveOneCodeAndIndexIsCleaned() throws Exception {
        BrowserSession browser = new BrowserSession();
        UUID userId = register(browser, "bind-code-concurrent@" + suffix() + ".example.com");
        String csrf = browser.csrf();

        int workers = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            var ready = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    return browser.post("/api/plugin/bind-code", "", csrf,
                            Map.of("Idempotency-Key", "concurrent-bind-code-key"));
                }));
            }
            ready.countDown();
            String code = null;
            for (var future : futures) {
                HttpResponse<String> response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                String candidate = json(response).at("/data/bindCode").asText();
                if (code == null) {
                    code = candidate;
                }
                assertThat(candidate).isEqualTo(code);
            }
            assertThat(code).isNotNull();

            // The code is consumable exactly once; the Redis value key and the
            // user index member are removed atomically.
            String installationId = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            HttpResponse<String> bound = new PluginClient().post("/api/plugin/bind", """
                    {"bindCode":"%s","installationId":"%s","deviceName":"并发设备",
                     "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                     "capabilities":["BOSS"]}
                    """.formatted(code, installationId));
            assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);
            HttpResponse<String> second = new PluginClient().post("/api/plugin/bind", """
                    {"bindCode":"%s","installationId":"%s","deviceName":"并发设备2",
                     "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                     "capabilities":["BOSS"]}
                    """.formatted(code, installationId + "extra"));
            assertThat(second.statusCode()).isEqualTo(401);

            assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + code)).isFalse();
            assertThat(redis.opsForZSet().size("ai-jobpilot:plugin:bind-code:user:" + userId)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void confirmWithoutDeviceSucceedsAndTheClaimingDeviceBindsAtStart() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "confirm-null-device@" + suffix() + ".example.com");
        PluginSession deviceA = bindDevice(user, userId, "赢家设备", "[\"BOSS\"]");
        PluginSession deviceB = bindDevice(user, userId, "败者设备", "[\"BOSS\"]");

        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "空设备确认");
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "null-device-create"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID taskId = UUID.fromString(json(created).at("/data/id").asText());

        // Confirm without any device: 200, CONFIRMED, no assigned device.
        HttpResponse<String> confirmed = user.post(
                "/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "null-device-confirm"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(confirmed).at("/data/assignedDeviceId").isNull()).isTrue();
        int confirmedVersion = json(confirmed).at("/data/version").asInt();

        // The task is visible to both capable devices; device A claims it.
        PluginClient pluginA = new PluginClient(deviceA.token());
        assertThat(json(pluginA.get("/api/plugin/tasks/pending")).at("/data/items").toString())
                .contains(taskId.toString());
        assertThat(json(new PluginClient(deviceB.token()).get("/api/plugin/tasks/pending")).at("/data/items").toString())
                .contains(taskId.toString());

        HttpResponse<String> started = pluginA.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + confirmedVersion + ",\"executionId\":\"exec-null-dev-1\","
                        + "\"extensionVersion\":\"1.2.0\",\"pageUrl\":\"https://www.zhipin.com/job_detail/test.html\"}",
                Map.of("Idempotency-Key", "null-device-start-a"));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        String lease = json(started).at("/data/leaseId").asText();
        int executingVersion = json(started).at("/data/version").asInt();

        // The winner's device is now bound to the task.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            var rows = stmt.executeQuery(
                    "SELECT assigned_device_id::text FROM app.delivery_tasks WHERE id='" + taskId + "'");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(deviceA.deviceId().toString());
        }

        // The other device cannot claim or report back even with the lease data.
        HttpResponse<String> loserStart = new PluginClient(deviceB.token()).post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + confirmedVersion + ",\"executionId\":\"exec-null-dev-2\","
                        + "\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "null-device-start-b"));
        assertThat(loserStart.statusCode()).isEqualTo(409);
        HttpResponse<String> loserFinish = new PluginClient(deviceB.token()).post(
                "/api/plugin/tasks/" + taskId + "/success",
                "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-null-dev-1\",\"version\":" + executingVersion
                        + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\","
                        + "\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\"}}",
                Map.of("Idempotency-Key", "null-device-finish-b"));
        assertThat(loserFinish.statusCode()).isEqualTo(409);
        assertThat(json(loserFinish).at("/error/code").asText()).isEqualTo("LEASE_INVALID");
    }

    @Test
    void startRequiresBothTaskScopesAndFinishRequiresWriteScope() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "scopes@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "范围设备", "[\"BOSS\"]");

        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "范围测试");
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "scope-create"));
        UUID taskId = UUID.fromString(json(created).at("/data/id").asText());
        HttpResponse<String> confirmed = user.post(
                "/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "scope-confirm"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        int version = json(confirmed).at("/data/version").asInt();

        PluginClient plugin = new PluginClient(device.token());
        String startBody = "{\"version\":" + version + ",\"executionId\":\"exec-scope-1\","
                + "\"extensionVersion\":\"1.2.0\"}";

        // Write-only token: pending needs read, start needs both.
        setTokenScopes(userId, device.deviceId(), "[\"tasks:write\"]");
        assertThat(plugin.get("/api/plugin/tasks/pending").statusCode()).isEqualTo(403);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/start", startBody,
                Map.of("Idempotency-Key", "scope-start-write-only")).statusCode()).isEqualTo(403);

        // Read-only token: pending works, start and finish do not.
        setTokenScopes(userId, device.deviceId(), "[\"tasks:read\"]");
        assertThat(plugin.get("/api/plugin/tasks/pending").statusCode()).isEqualTo(200);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/start", startBody,
                Map.of("Idempotency-Key", "scope-start-read-only")).statusCode()).isEqualTo(403);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/pause", "{}",
                Map.of("Idempotency-Key", "scope-pause-read-only")).statusCode()).isEqualTo(403);

        // Restoring both scopes lets the flow proceed.
        setTokenScopes(userId, device.deviceId(), "[\"device:read\",\"tasks:read\",\"tasks:write\"]");
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/start", startBody,
                Map.of("Idempotency-Key", "scope-start-full")).statusCode()).isEqualTo(200);
    }

    @Test
    void finishEndpointsRequireIdempotencyKeysAndRejectPayloadConflicts() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "finish-idem@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "幂等设备", "[\"BOSS\"]");

        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "幂等测试");
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "idem-create"));
        UUID taskId = UUID.fromString(json(created).at("/data/id").asText());
        HttpResponse<String> confirmed = user.post(
                "/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "idem-confirm"));
        int confirmedVersion = json(confirmed).at("/data/version").asInt();
        PluginClient plugin = new PluginClient(device.token());

        HttpResponse<String> started = plugin.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":" + confirmedVersion + ",\"executionId\":\"exec-idem-1\","
                        + "\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "idem-start"));
        String lease = json(started).at("/data/leaseId").asText();
        int executingVersion = json(started).at("/data/version").asInt();

        // Missing Idempotency-Key is rejected on every write endpoint.
        String successBody = "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-idem-1\",\"version\":" + executingVersion
                + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\","
                + "\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\"}}";
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success", successBody).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/fail",
                "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-idem-1\",\"version\":" + executingVersion
                        + ",\"errorCode\":\"NETWORK_ERROR\",\"message\":\"无键\"}").statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/pause",
                "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-idem-1\",\"version\":" + executingVersion
                        + ",\"reason\":\"PAGE_CHANGED\"}").statusCode()).isEqualTo(400);

        // Identical replay succeeds; a different payload for the same execution
        // or the same Idempotency-Key conflicts.
        HttpResponse<String> success = plugin.post(
                "/api/plugin/tasks/" + taskId + "/success", successBody,
                Map.of("Idempotency-Key", "idem-success"));
        assertThat(success.statusCode()).as(success.body()).isEqualTo(200);
        HttpResponse<String> replay = plugin.post(
                "/api/plugin/tasks/" + taskId + "/success", successBody,
                Map.of("Idempotency-Key", "idem-success"));
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        HttpResponse<String> differentEvidence = plugin.post(
                "/api/plugin/tasks/" + taskId + "/success",
                "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-idem-1\",\"version\":" + executingVersion
                        + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\","
                        + "\"evidence\":{\"pageState\":\"ALREADY_DELIVERED\"}}",
                Map.of("Idempotency-Key", "idem-success"));
        assertThat(differentEvidence.statusCode()).isEqualTo(409);
        assertThat(json(differentEvidence).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // The same key on a different endpoint payload is a conflict too.
        HttpResponse<String> sameKeyDifferentCall = plugin.post(
                "/api/plugin/tasks/" + taskId + "/fail",
                "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-idem-1\",\"version\":" + executingVersion
                        + ",\"errorCode\":\"NETWORK_ERROR\",\"message\":\"复用键\"}",
                Map.of("Idempotency-Key", "idem-success"));
        assertThat(sameKeyDifferentCall.statusCode()).isEqualTo(409);
        assertThat(json(sameKeyDifferentCall).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Exactly one SUCCEEDED event and one PLUGIN_TASK_SUCCEEDED audit row.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId + "' "
                            + "AND event_type='SUCCEEDED'"))).isEqualTo(1);
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.audit_logs WHERE user_id='" + userId + "' "
                            + "AND action='PLUGIN_TASK_SUCCEEDED'"))).isEqualTo(1);
        }
    }

    // ---- 6. Trusted job URLs, evidence whitelist and message sanitization ----

    @Test
    void untrustedJobUrlsNeverReachThePluginAndQueriesAreStripped() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "urls@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "URL设备", "[\"BOSS\",\"ZHILIAN\"]");
        PluginClient plugin = new PluginClient(device.token());

        // Search/home pages and wrong hosts are never handed out; a valid URL
        // with tracking query parameters is normalized to origin + path.
        UUID searchJob = seedJobWithUrl(userId, "BOSS", "https://www.zhipin.com/web/geek/job?query=java");
        UUID homeJob = seedJobWithUrl(userId, "ZHILIAN", "https://sou.zhaopin.com/");
        UUID queryJob = seedJobWithUrl(userId, "BOSS",
                "https://www.zhipin.com/job_detail/abc.html?ka=tracking&sid=7");
        seedMatch(userId, searchJob, "SUCCEEDED", "APPLY", "搜索页");
        seedMatch(userId, homeJob, "SUCCEEDED", "APPLY", null);
        seedMatch(userId, queryJob, "SUCCEEDED", "APPLY", "带参数岗位");

        UUID searchTask = createAndConfirm(user, searchJob, device.deviceId(), "urls-search");
        UUID homeTask = createAndConfirm(user, homeJob, device.deviceId(), "urls-home");
        UUID queryTask = createAndConfirm(user, queryJob, device.deviceId(), "urls-query");

        // The pending list excludes the untrusted rows and normalizes the valid one.
        String pendingJson = plugin.get("/api/plugin/tasks/pending").body();
        assertThat(pendingJson).contains(queryTask.toString());
        assertThat(pendingJson).contains("https://www.zhipin.com/job_detail/abc.html");
        assertThat(pendingJson).doesNotContain("ka=tracking");
        assertThat(pendingJson).doesNotContain(searchTask.toString());
        assertThat(pendingJson).doesNotContain(homeTask.toString());

        // start rejects tasks whose stored job URL is untrusted, before any change.
        HttpResponse<String> badStart = plugin.post(
                "/api/plugin/tasks/" + searchTask + "/start",
                "{\"version\":2,\"executionId\":\"exec-urls-bad\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "urls-bad-start"));
        assertThat(badStart.statusCode()).isEqualTo(422);
        assertThat(json(badStart).at("/error/code").asText()).isEqualTo("UNTRUSTED_JOB_URL");
        HttpResponse<String> homeStart = plugin.post(
                "/api/plugin/tasks/" + homeTask + "/start",
                "{\"version\":2,\"executionId\":\"exec-urls-home\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "urls-home-start"));
        assertThat(homeStart.statusCode()).isEqualTo(422);

        // A valid start returns the normalized URL (no query, no fragment).
        HttpResponse<String> started = plugin.post(
                "/api/plugin/tasks/" + queryTask + "/start",
                "{\"version\":2,\"executionId\":\"exec-urls-ok\",\"extensionVersion\":\"1.2.0\","
                        + "\"pageUrl\":\"https://www.zhipin.com/job_detail/abc.html\"}",
                Map.of("Idempotency-Key", "urls-ok-start"));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json(started).at("/data/task/jobUrl").asText())
                .isEqualTo("https://www.zhipin.com/job_detail/abc.html");

        // Client pageUrl with a tracking query or on a wrong host is rejected.
        UUID cleanJob = seedJobWithUrl(userId, "BOSS", "https://www.zhipin.com/job_detail/clean.html");
        seedMatch(userId, cleanJob, "SUCCEEDED", "APPLY", "干净岗位");
        UUID cleanTask = createAndConfirm(user, cleanJob, device.deviceId(), "urls-clean");
        HttpResponse<String> queryPageUrl = plugin.post(
                "/api/plugin/tasks/" + cleanTask + "/start",
                "{\"version\":2,\"executionId\":\"exec-urls-q\",\"extensionVersion\":\"1.2.0\","
                        + "\"pageUrl\":\"https://www.zhipin.com/job_detail/clean.html?ka=x\"}",
                Map.of("Idempotency-Key", "urls-query-start"));
        assertThat(queryPageUrl.statusCode()).isEqualTo(422);
        assertThat(json(queryPageUrl).at("/error/code").asText()).isEqualTo("UNTRUSTED_JOB_URL");
        HttpResponse<String> evilPageUrl = plugin.post(
                "/api/plugin/tasks/" + cleanTask + "/start",
                "{\"version\":2,\"executionId\":\"exec-urls-evil\",\"extensionVersion\":\"1.2.0\","
                        + "\"pageUrl\":\"https://zhipin.com.evil.example.com/job_detail/clean.html\"}",
                Map.of("Idempotency-Key", "urls-evil-start"));
        assertThat(evilPageUrl.statusCode()).isEqualTo(422);
    }

    @Test
    void evidenceIsAStrictWhitelistAndMessagesRejectSensitiveContent() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "evidence@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "证据设备", "[\"BOSS\"]");
        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "证据测试");
        UUID taskId = createAndConfirm(user, jobId, device.deviceId(), "evidence-create");
        PluginClient plugin = new PluginClient(device.token());

        HttpResponse<String> started = plugin.post(
                "/api/plugin/tasks/" + taskId + "/start",
                "{\"version\":2,\"executionId\":\"exec-evidence-1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "evidence-start"));
        String lease = json(started).at("/data/leaseId").asText();
        int version = json(started).at("/data/version").asInt();

        String base = "{\"leaseId\":\"" + lease + "\",\"executionId\":\"exec-evidence-1\",\"version\":" + version
                + ",\"completedAt\":\"2026-08-13T10:00:00Z\",\"resultCode\":\"DELIVERED\"";
        // Unknown keys, nested content, wrong values and missing evidence are rejected.
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + ",\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\",\"screenshot\":\"data:image/png;base64,x\"}}",
                Map.of("Idempotency-Key", "ev-1")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + ",\"evidence\":{\"pageState\":{\"nested\":true}}}",
                Map.of("Idempotency-Key", "ev-2")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + ",\"evidence\":{\"pageState\":\"GOOD_ENOUGH\"}}",
                Map.of("Idempotency-Key", "ev-3")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + ",\"evidence\":{\"alreadyDelivered\":true}}",
                Map.of("Idempotency-Key", "ev-4")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + "}", Map.of("Idempotency-Key", "ev-5")).statusCode()).isEqualTo(400);
        // The strict whitelist works.
        HttpResponse<String> ok = plugin.post("/api/plugin/tasks/" + taskId + "/success",
                base + ",\"evidence\":{\"pageState\":\"SUCCESS_NOTICE\",\"alreadyDelivered\":false}}",
                Map.of("Idempotency-Key", "ev-ok"));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);

        // A second task exercises fail/pause message sanitization and the
        // server-side retryability rules.
        UUID job2 = seedApplicableJob(userId, "BOSS", "APPLY", "文本测试");
        UUID task2 = createAndConfirm(user, job2, device.deviceId(), "text-create");
        HttpResponse<String> started2 = plugin.post(
                "/api/plugin/tasks/" + task2 + "/start",
                "{\"version\":2,\"executionId\":\"exec-text-1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "text-start"));
        String lease2 = json(started2).at("/data/leaseId").asText();
        int version2 = json(started2).at("/data/version").asInt();
        String failBase = "{\"leaseId\":\"" + lease2 + "\",\"executionId\":\"exec-text-1\",\"version\":" + version2
                + ",\"failedAt\":\"2026-08-13T10:00:00Z\",\"errorCode\":\"NETWORK_ERROR\"";
        assertThat(plugin.post("/api/plugin/tasks/" + task2 + "/fail",
                failBase + ",\"message\":\"Cookie: session=secret\"}",
                Map.of("Idempotency-Key", "text-1")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + task2 + "/fail",
                failBase + ",\"message\":\"password=hunter2\"}",
                Map.of("Idempotency-Key", "text-2")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + task2 + "/fail",
                failBase + ",\"message\":\"第一行\\n第二行\"}",
                Map.of("Idempotency-Key", "text-3")).statusCode()).isEqualTo(400);
        assertThat(plugin.post("/api/plugin/tasks/" + task2 + "/pause",
                "{\"leaseId\":\"" + lease2 + "\",\"executionId\":\"exec-text-1\",\"version\":" + version2
                        + ",\"reason\":\"PAUSED_REASONABLE\",\"message\":\"Authorization: Bearer x\"}",
                Map.of("Idempotency-Key", "text-4")).statusCode()).isEqualTo(422);
        assertThat(plugin.post("/api/plugin/tasks/" + task2 + "/pause",
                "{\"leaseId\":\"" + lease2 + "\",\"executionId\":\"exec-text-1\",\"version\":" + version2
                        + ",\"reason\":\"PAGE_CHANGED\",\"message\":\"LocalStorage: token=abc\"}",
                Map.of("Idempotency-Key", "text-4b")).statusCode()).isEqualTo(400);
        // UNKNOWN_ERROR: the server decides retryability, ignoring the client value.
        HttpResponse<String> unknown = plugin.post("/api/plugin/tasks/" + task2 + "/fail",
                failBase.replace("NETWORK_ERROR", "UNKNOWN_ERROR") + ",\"message\":\"原因不明\",\"retryable\":false}",
                Map.of("Idempotency-Key", "text-unknown"));
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(200);
        assertThat(json(unknown).at("/data/retryable").asBoolean()).isTrue();
    }

    // ---- 7. Sentinel hygiene ----

    @Test
    void tokenAndHashSentinelsNeverLeakIntoAuditEventsOrResponses() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "sentinel@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "哨兵设备", "[\"BOSS\"]");
        String token = device.token();
        String tokenHash = sha256Hex(token);
        PluginClient plugin = new PluginClient(token);

        // Drive a few plugin responses and check none of them echo the secret.
        String meBody = plugin.get("/api/plugin/me").body();
        assertThat(meBody).doesNotContain(token).doesNotContain(tokenHash);
        String pendingBody = plugin.get("/api/plugin/tasks/pending").body();
        assertThat(pendingBody).doesNotContain(token).doesNotContain(tokenHash);

        // Audit rows and domain events never carry the plaintext or the hash.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.audit_logs WHERE details::text LIKE '%" + token + "%' " +
                            "OR details::text LIKE '%" + tokenHash + "%'"))).isZero();
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE details::text LIKE '%" + token + "%' " +
                            "OR details::text LIKE '%" + tokenHash + "%'"))).isZero();
            // The hash lives only in plugin_tokens.token_hash.
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.plugin_tokens WHERE user_id='" + userId + "' " +
                            "AND token_hash='" + tokenHash + "'"))).isEqualTo(1);
        }
    }

    @Test
    void bindRejectsAccountsDisabledAfterTheCodeWasIssued() throws Exception {
        BrowserSession browser = new BrowserSession();
        UUID userId = register(browser, "bind-disabled@" + suffix() + ".example.com");
        HttpResponse<String> bindCode = browser.post(
                "/api/plugin/bind-code", "", browser.csrf(),
                Map.of("Idempotency-Key", "disabled-bind-code"));
        assertThat(bindCode.statusCode()).isEqualTo(201);
        String code = json(bindCode).at("/data/bindCode").asText();

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.users SET status='DISABLED' WHERE id='" + userId + "'");
        }

        HttpResponse<String> bound = new PluginClient().post("/api/plugin/bind", """
                {"bindCode":"%s","installationId":"%s","deviceName":"禁用设备",
                 "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                 "capabilities":["BOSS"]}
                """.formatted(code, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")));
        assertThat(bound.statusCode()).isEqualTo(403);
        assertThat(json(bound).at("/error/code").asText()).isEqualTo("ACCOUNT_DISABLED");

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.plugin_devices WHERE user_id='" + userId + "'"))).isZero();
        }
    }

    // ---- 8. Bind-code activity cap against real Redis (regression) ----

    @Test
    void bindCodeCapEvictsOnlyTheOldestAndConsumptionCleansAtomically() {
        UUID userId = UUID.randomUUID();
        List<String> codes = new ArrayList<>();
        // The configured cap is 3: a 4th code must evict only the OLDEST value
        // key in the same atomic script, never the live ones (the previous
        // script purged every existing code on each create).
        for (int i = 1; i <= 4; i++) {
            codes.add(bindCodeService.create(userId, "cap-key-" + i).bindCode());
        }
        String userIndex = "ai-jobpilot:plugin:bind-code:user:" + userId;
        assertThat(redis.opsForZSet().size(userIndex)).isEqualTo(3);
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + codes.get(0))).isFalse();
        for (int i = 1; i <= 3; i++) {
            assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + codes.get(i))).isTrue();
        }
        // Consuming a valid code removes value + attempt + index member in one
        // atomic step and leaves the other live codes untouched.
        bindCodeService.checkAttemptLimit(codes.get(1));
        assertThat(bindCodeService.consume(codes.get(1))).contains(userId);
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + codes.get(1))).isFalse();
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:attempts:" + codes.get(1))).isFalse();
        assertThat(redis.opsForZSet().size(userIndex)).isEqualTo(2);
        assertThat(bindCodeService.consume(codes.get(1))).isEmpty();
        assertThat(bindCodeService.consume(codes.get(2))).contains(userId);
        assertThat(bindCodeService.consume(codes.get(3))).contains(userId);
        // The evicted oldest code is gone for good.
        assertThat(bindCodeService.consume(codes.get(0))).isEmpty();
        assertThat(redis.opsForZSet().size(userIndex)).isZero();
    }

    @Test
    void creatingMoreCodesBeforeExpiryDoesNotEvictLiveOnes() {
        UUID userId = UUID.randomUUID();
        var first = bindCodeService.create(userId, "keep-key-1").bindCode();
        var second = bindCodeService.create(userId, "keep-key-2").bindCode();
        var third = bindCodeService.create(userId, "keep-key-3").bindCode();
        String userIndex = "ai-jobpilot:plugin:bind-code:user:" + userId;
        assertThat(redis.opsForZSet().size(userIndex)).isEqualTo(3);
        // While unexpired, the first code must survive later creations (the
        // old script evicted it because the purge bound was the new expiry).
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + first)).isTrue();
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + second)).isTrue();
        assertThat(redis.hasKey("ai-jobpilot:plugin:bind-code:value:" + third)).isTrue();
        assertThat(bindCodeService.consume(first)).contains(userId);
    }

    // ---- 9. Web confirm/skip concurrency, idempotency and in-transaction audit ----

    @Test
    void concurrentIdenticalConfirmWritesOneTransitionOneEventOneAudit() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "confirm-race@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "并发确认设备", "[\"BOSS\"]");
        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "并发确认问候");
        UUID taskId = createTask(user, jobId, "confirm-race-create");
        String body = "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\""
                + device.deviceId() + "\"}";
        String csrf = user.csrf();

        int workers = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            var ready = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    return user.post("/api/delivery/tasks/" + taskId + "/confirm", body, csrf,
                            Map.of("Idempotency-Key", "confirm-race-key"));
                }));
            }
            ready.countDown();
            for (var future : futures) {
                HttpResponse<String> response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(json(response).at("/data/status").asText()).isEqualTo("CONFIRMED");
                assertThat(json(response).at("/data/version").asInt()).isEqualTo(2);
                assertThat(json(response).at("/data/confirmationVersion").asInt()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        // Exactly one domain event and one audit row despite four requests.
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId
                            + "' AND event_type='CONFIRMED'"))).isEqualTo(1);
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.audit_logs WHERE user_id='" + userId
                            + "' AND action='DELIVERY_TASK_CONFIRMED'"))).isEqualTo(1);
        }
    }

    @Test
    void concurrentIdenticalSkipWritesOneTransitionOneEventOneAudit() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "skip-race@" + suffix() + ".example.com");
        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "并发跳过");
        UUID taskId = createTask(user, jobId, "skip-race-create");
        String body = "{\"version\":1,\"reason\":\"NOT_INTERESTED\"}";
        String csrf = user.csrf();

        int workers = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            var ready = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    return user.post("/api/delivery/tasks/" + taskId + "/skip", body, csrf,
                            Map.of("Idempotency-Key", "skip-race-key"));
                }));
            }
            ready.countDown();
            for (var future : futures) {
                HttpResponse<String> response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(json(response).at("/data/status").asText()).isEqualTo("SKIPPED");
                assertThat(json(response).at("/data/version").asInt()).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id='" + taskId
                            + "' AND event_type='SKIPPED'"))).isEqualTo(1);
            assertThat(singleLong(stmt.executeQuery(
                    "SELECT count(*) FROM app.audit_logs WHERE user_id='" + userId
                            + "' AND action='DELIVERY_TASK_SKIPPED'"))).isEqualTo(1);
        }
    }

    @Test
    void reusingAnIdempotencyKeyAcrossConfirmAndSkipConflictsStably() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "cross-idem@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "跨端点设备", "[\"BOSS\"]");
        UUID jobId = seedApplicableJob(userId, "BOSS", "APPLY", "跨端点");
        UUID taskId = createTask(user, jobId, "cross-create");
        assertThat(user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "cross-key")).statusCode()).isEqualTo(200);

        // The same key on the other endpoint must be a stable 409 conflict,
        // never a unique-index violation surfacing as a 500.
        HttpResponse<String> cross = user.post("/api/delivery/tasks/" + taskId + "/skip",
                "{\"version\":2,\"reason\":\"NOT_INTERESTED\"}",
                user.csrf(), Map.of("Idempotency-Key", "cross-key"));
        assertThat(cross.statusCode()).isEqualTo(409);
        assertThat(json(cross).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        // Reusing the key with a different payload on the same endpoint also
        // conflicts stably.
        HttpResponse<String> differentPayload = user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":2,\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "cross-key"));
        assertThat(differentPayload.statusCode()).isEqualTo(409);
        assertThat(json(differentPayload).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        // A stale version (fresh key) maps to the version conflict, not a 500.
        HttpResponse<String> stale = user.post("/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true}",
                user.csrf(), Map.of("Idempotency-Key", "stale-confirm-key"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).at("/error/code").asText()).isEqualTo("RESOURCE_VERSION_CONFLICT");
    }

    // ---- 10. Lease recovery releases the device; greeting edits invalidate ----

    @Test
    void expiredLeaseRecoveryReleasesTheDeviceAndExhaustedTasksFailWithoutOne() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "lease-release@" + suffix() + ".example.com");
        PluginSession deviceA = bindDevice(user, userId, "租约设备A", "[\"BOSS\"]");
        PluginSession deviceB = bindDevice(user, userId, "租约设备B", "[\"BOSS\"]");
        PluginClient pluginB = new PluginClient(deviceB.token());

        // Recovery under the attempt cap: CONFIRMED again, device released.
        UUID jobA = seedApplicableJob(userId, "BOSS", "APPLY", "租约释放A");
        UUID taskA = createTask(user, jobA, "lease-release-create-a");
        assertThat(user.post("/api/delivery/tasks/" + taskA + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceA.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "lease-release-confirm-a")).statusCode()).isEqualTo(200);
        assertThat(new PluginClient(deviceA.token()).post(
                "/api/plugin/tasks/" + taskA + "/start",
                "{\"version\":2,\"executionId\":\"exec-release-a1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "lease-release-start-a")).statusCode()).isEqualTo(200);
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.delivery_tasks SET lease_expires_at = now() - interval '1 second' "
                    + "WHERE id='" + taskA + "'");
        }
        assertThat(deliveryService.recoverExpiredLeases()).isGreaterThanOrEqualTo(1);
        HttpResponse<String> detailA = user.get("/api/delivery/tasks/" + taskA);
        assertThat(json(detailA).at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(json(detailA).at("/data/assignedDeviceId").isNull()).isTrue();
        int recoveredVersion = json(detailA).at("/data/version").asInt();
        // The released task is claimable by a different capable device.
        HttpResponse<String> reclaim = pluginB.post(
                "/api/plugin/tasks/" + taskA + "/start",
                "{\"version\":" + recoveredVersion + ",\"executionId\":\"exec-release-b1\","
                        + "\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "lease-release-start-b"));
        assertThat(reclaim.statusCode()).as(reclaim.body()).isEqualTo(200);

        // Recovery at the attempt cap: FAILED without an assigned device.
        UUID jobB = seedApplicableJob(userId, "BOSS", "APPLY", "租约释放B");
        UUID taskB = createTask(user, jobB, "lease-release-create-b");
        assertThat(user.post("/api/delivery/tasks/" + taskB + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceA.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "lease-release-confirm-b")).statusCode()).isEqualTo(200);
        assertThat(new PluginClient(deviceA.token()).post(
                "/api/plugin/tasks/" + taskB + "/start",
                "{\"version\":2,\"executionId\":\"exec-release-a2\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "lease-release-start-a2")).statusCode()).isEqualTo(200);
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.delivery_tasks SET lease_expires_at = now() - interval '1 second', "
                    + "attempt_count = 3 WHERE id='" + taskB + "'");
        }
        assertThat(deliveryService.recoverExpiredLeases()).isGreaterThanOrEqualTo(1);
        HttpResponse<String> detailB = user.get("/api/delivery/tasks/" + taskB);
        assertThat(json(detailB).at("/data/status").asText()).isEqualTo("FAILED");
        assertThat(json(detailB).at("/data/assignedDeviceId").isNull()).isTrue();
        assertThat(json(detailB).at("/data/lastError/code").asText()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
    }

    @Test
    void greetingEditsInvalidateConfirmationsAndTerminalFailuresCannotBeEdited() throws Exception {
        BrowserSession user = new BrowserSession();
        UUID userId = register(user, "greeting-invalidate@" + suffix() + ".example.com");
        PluginSession device = bindDevice(user, userId, "招呼语设备", "[\"BOSS\"]");
        PluginClient plugin = new PluginClient(device.token());

        // CONFIRMED edit: back to PENDING_CONFIRMATION, confirmation invalidated.
        UUID jobA = seedApplicableJob(userId, "BOSS", "APPLY", "原始招呼语");
        UUID taskA = createTask(user, jobA, "greet-a-create");
        assertThat(user.post("/api/delivery/tasks/" + taskA + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "greet-a-confirm")).statusCode()).isEqualTo(200);
        HttpResponse<String> editedA = user.put("/api/delivery/tasks/" + taskA + "/greeting",
                "{\"version\":2,\"greeting\":\"修改后的招呼语\"}", user.csrf());
        assertThat(editedA.statusCode()).as(editedA.body()).isEqualTo(200);
        assertThat(json(editedA).at("/data/status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(json(editedA).at("/data/confirmationRequired").asBoolean()).isTrue();
        HttpResponse<String> detailA = user.get("/api/delivery/tasks/" + taskA);
        assertThat(json(detailA).at("/data/assignedDeviceId").isNull()).isTrue();
        String eventsA = json(detailA).at("/data/events").toString();
        assertThat(eventsA).contains("GREETING_UPDATED").contains("CONFIRMATION_INVALIDATED");
        // The unconfirmed task disappears from the plugin pending list again.
        assertThat(json(plugin.get("/api/plugin/tasks/pending")).at("/data/items").toString())
                .doesNotContain(taskA.toString());

        // PAUSED edit: back to PENDING_CONFIRMATION as well.
        UUID jobB = seedApplicableJob(userId, "BOSS", "APPLY", "暂停任务");
        UUID taskB = createTask(user, jobB, "greet-b-create");
        assertThat(user.post("/api/delivery/tasks/" + taskB + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "greet-b-confirm")).statusCode()).isEqualTo(200);
        HttpResponse<String> startB = plugin.post(
                "/api/plugin/tasks/" + taskB + "/start",
                "{\"version\":2,\"executionId\":\"exec-greet-b1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "greet-b-start"));
        String leaseB = json(startB).at("/data/leaseId").asText();
        HttpResponse<String> paused = plugin.post(
                "/api/plugin/tasks/" + taskB + "/pause",
                "{\"leaseId\":\"" + leaseB + "\",\"executionId\":\"exec-greet-b1\",\"version\":3,"
                        + "\"reason\":\"CAPTCHA_REQUIRED\",\"message\":\"需要人工验证\"}",
                Map.of("Idempotency-Key", "greet-b-pause"));
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        HttpResponse<String> editedB = user.put("/api/delivery/tasks/" + taskB + "/greeting",
                "{\"version\":4,\"greeting\":\"暂停后修改\"}", user.csrf());
        assertThat(editedB.statusCode()).as(editedB.body()).isEqualTo(200);
        assertThat(json(editedB).at("/data/status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(json(user.get("/api/delivery/tasks/" + taskB)).at("/data/events").toString())
                .contains("CONFIRMATION_INVALIDATED");

        // Retryable FAILED edit: back to PENDING_CONFIRMATION with errors cleared.
        UUID jobC = seedApplicableJob(userId, "BOSS", "APPLY", "可重试失败");
        UUID taskC = createTask(user, jobC, "greet-c-create");
        assertThat(user.post("/api/delivery/tasks/" + taskC + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "greet-c-confirm")).statusCode()).isEqualTo(200);
        HttpResponse<String> startC = plugin.post(
                "/api/plugin/tasks/" + taskC + "/start",
                "{\"version\":2,\"executionId\":\"exec-greet-c1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "greet-c-start"));
        String leaseC = json(startC).at("/data/leaseId").asText();
        HttpResponse<String> failedC = plugin.post(
                "/api/plugin/tasks/" + taskC + "/fail",
                "{\"leaseId\":\"" + leaseC + "\",\"executionId\":\"exec-greet-c1\",\"version\":3,"
                        + "\"errorCode\":\"BUTTON_NOT_FOUND\",\"message\":\"按钮不存在\"}",
                Map.of("Idempotency-Key", "greet-c-fail"));
        assertThat(failedC.statusCode()).as(failedC.body()).isEqualTo(200);
        assertThat(json(failedC).at("/data/retryable").asBoolean()).isTrue();
        HttpResponse<String> editedC = user.put("/api/delivery/tasks/" + taskC + "/greeting",
                "{\"version\":4,\"greeting\":\"失败后修改\"}", user.csrf());
        assertThat(editedC.statusCode()).as(editedC.body()).isEqualTo(200);
        assertThat(json(editedC).at("/data/status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(json(user.get("/api/delivery/tasks/" + taskC)).at("/data/lastError").isNull()).isTrue();

        // Non-retryable FAILED (JOB_CLOSED): editing cannot reopen the terminal
        // business outcome.
        UUID jobD = seedApplicableJob(userId, "BOSS", "APPLY", "已关闭岗位");
        UUID taskD = createTask(user, jobD, "greet-d-create");
        assertThat(user.post("/api/delivery/tasks/" + taskD + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + device.deviceId() + "\"}",
                user.csrf(), Map.of("Idempotency-Key", "greet-d-confirm")).statusCode()).isEqualTo(200);
        HttpResponse<String> startD = plugin.post(
                "/api/plugin/tasks/" + taskD + "/start",
                "{\"version\":2,\"executionId\":\"exec-greet-d1\",\"extensionVersion\":\"1.2.0\"}",
                Map.of("Idempotency-Key", "greet-d-start"));
        String leaseD = json(startD).at("/data/leaseId").asText();
        HttpResponse<String> closed = plugin.post(
                "/api/plugin/tasks/" + taskD + "/fail",
                "{\"leaseId\":\"" + leaseD + "\",\"executionId\":\"exec-greet-d1\",\"version\":3,"
                        + "\"errorCode\":\"JOB_CLOSED\",\"message\":\"岗位已关闭\"}",
                Map.of("Idempotency-Key", "greet-d-fail"));
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(json(closed).at("/data/retryable").asBoolean()).isFalse();
        HttpResponse<String> editedD = user.put("/api/delivery/tasks/" + taskD + "/greeting",
                "{\"version\":4,\"greeting\":\"绕过终态\"}", user.csrf());
        assertThat(editedD.statusCode()).isEqualTo(409);
        assertThat(json(editedD).at("/error/code").asText()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    // ---- helpers ----

    /** A bound device together with its one-time plaintext token. */
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

    /** Binds a fresh device for the user's Web session and returns device id + token. */
    private PluginSession bindDevice(BrowserSession browser, UUID userId, String deviceName, String capabilities)
            throws Exception {
        HttpResponse<String> bindCode = browser.post(
                "/api/plugin/bind-code", "", browser.csrf(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        String code = json(bindCode).at("/data/bindCode").asText();
        String installationId = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> bound = new PluginClient().post("/api/plugin/bind", """
                {"bindCode":"%s","installationId":"%s","deviceName":"%s",
                 "browserName":"Chrome","browserVersion":"120","extensionVersion":"1.2.0",
                 "capabilities":%s}
                """.formatted(code, installationId, deviceName, capabilities));
        assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);
        return new PluginSession(
                UUID.fromString(json(bound).at("/data/device/id").asText()),
                json(bound).at("/data/token/value").asText()
        );
    }

    private UUID seedApplicableJob(UUID userId, String platform, String decision, String greeting)
            throws Exception {
        UUID jobId = seedJob(userId, platform);
        seedMatch(userId, jobId, "SUCCEEDED", decision, greeting);
        return jobId;
    }

    private UUID seedJob(UUID userId, String platform) throws Exception {
        return seedJobWithUrl(userId, platform, "BOSS".equals(platform)
                ? "https://www.zhipin.com/job_detail/test.html"
                : "https://sou.zhaopin.com/jobs/jobdetail/test");
    }

    /** Seeds a job post with an arbitrary job URL (for URL trust tests). */
    private UUID seedJobWithUrl(UUID userId, String platform, String jobUrl) throws Exception {
        UUID jobId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); var stmt = owner.prepareStatement("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, ?, ?, 'Java工程师', '示例公司', ?, now(), now())
                """)) {
            stmt.setObject(1, jobId);
            stmt.setObject(2, userId);
            stmt.setString(3, platform);
            stmt.setString(4, hex(UUID.randomUUID().toString() + UUID.randomUUID()));
            stmt.setString(5, jobUrl);
            stmt.executeUpdate();
        }
        return jobId;
    }

    /** Creates a delivery task for the job without confirming it (version 1). */
    private UUID createTask(BrowserSession user, UUID jobId, String key) throws Exception {
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).at("/data/id").asText());
    }

    /** Creates a task for the job and confirms it onto the given device (version 2). */
    private UUID createAndConfirm(BrowserSession user, UUID jobId, UUID deviceId, String key)
            throws Exception {
        HttpResponse<String> created = user.post(
                "/api/delivery/tasks", "{\"jobPostId\":\"" + jobId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", key));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID taskId = UUID.fromString(json(created).at("/data/id").asText());
        HttpResponse<String> confirmed = user.post(
                "/api/delivery/tasks/" + taskId + "/confirm",
                "{\"version\":1,\"acknowledged\":true,\"assignedDeviceId\":\"" + deviceId + "\"}",
                user.csrf(), Map.of("Idempotency-Key", key + "-confirm"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("CONFIRMED");
        return taskId;
    }

    /** Overwrites the scopes of the device's active token (scope enforcement tests). */
    private void setTokenScopes(UUID userId, UUID deviceId, String scopesJson) throws Exception {
        try (Connection owner = ownerConnection(); var stmt = owner.createStatement()) {
            stmt.execute("UPDATE app.plugin_tokens SET scopes='" + scopesJson + "'::jsonb "
                    + "WHERE user_id='" + userId + "' AND plugin_device_id='" + deviceId + "'");
        }
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static long singleLong(java.sql.ResultSet resultSet) throws Exception {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    /** One current resume + one current preference per user (unique indexes enforce this). */
    private static final Map<UUID, UUID> RESUME_BY_USER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, UUID> PREFERENCE_BY_USER = new java.util.concurrent.ConcurrentHashMap<>();

    private UUID seedMatch(UUID userId, UUID jobId, String status, String decision, String greeting)
            throws Exception {
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
                        decision, greeting, input_fingerprint, completed_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', '%s', %s, %s, '%s', %s
                    )
                    """.formatted(matchId, userId, jobId, resumeId, preferenceId, status,
                    decision == null ? "NULL" : "'" + decision + "'",
                    greeting == null ? "NULL" : "'" + greeting + "'",
                    hex(UUID.randomUUID().toString() + UUID.randomUUID()),
                    "SUCCEEDED".equals(status) ? "now()" : "NULL"));
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
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        '%s', '%s', 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, userId, hex(resumeId.toString()),
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

    private static String suffix() {
        return Long.toHexString(System.nanoTime());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return new ObjectMapper().readTree(response.body());
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

        PluginClient() {
            this(null);
        }

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
            if (headers != null) {
                headers.forEach(builder::header);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }
}
