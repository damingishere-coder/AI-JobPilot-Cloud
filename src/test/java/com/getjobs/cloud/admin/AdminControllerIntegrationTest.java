package com.getjobs.cloud.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.CloudApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/admin 后台接口端到端测试：未登录 401、普通用户 403、ACTIVE ADMIN 可用
 * 全部 7 个接口、调额幂等与 422、404 不泄露枚举、响应不含完整邮箱与敏感字段。
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
                "spring.flyway.placeholders.app_role=jobpilot_owner",
                "app.auth.hash-pepper=integration-admin-test-pepper-at-least-32-bytes",
                "app.auth.login-ip-limit=100",
                "app.auth.login-email-limit=100",
                "app.auth.register-ip-limit=100",
                "app.auth.csrf-ip-limit=100",
                "app.ai-match.outbox-poll-delay=365d"
        }
)
class AdminControllerIntegrationTest {
    private static final String REDIS_PASSWORD = "integration_admin_redis_password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-admin-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    JdbcTemplate jdbc;

    @Test
    void adminEndpointsRequireLoginAndRejectNormalUsers() throws Exception {
        // 未登录访问后台接口 → 401 AUTH_REQUIRED。
        BrowserSession anonymous = new BrowserSession();
        assertThat(anonymous.get("/api/admin/users").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/admin/dashboard").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/admin/audit-logs").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/admin/delivery-failures").statusCode()).isEqualTo(401);

        // 普通 USER 访问后台接口 → 403 FORBIDDEN（GET 与 PUT 各代表一个）。
        BrowserSession normal = new BrowserSession();
        UUID normalId = register(normal, "admin-normal@example.com");
        assertThat(normal.get("/api/admin/users?page=0&size=20").statusCode()).isEqualTo(403);
        assertThat(normal.get("/api/admin/dashboard").statusCode()).isEqualTo(403);
        assertThat(normal.get("/api/admin/users/" + normalId).statusCode()).isEqualTo(403);
        assertThat(normal.get("/api/admin/users/" + normalId + "/quota").statusCode()).isEqualTo(403);
        assertThat(normal.get("/api/admin/audit-logs").statusCode()).isEqualTo(403);
        assertThat(normal.get("/api/admin/delivery-failures").statusCode()).isEqualTo(403);
        assertThat(normal.put(
                "/api/admin/users/" + normalId + "/quota",
                """
                {"plan":"MONTHLY","analysisQuotaTotal":100,"deliveryQuotaTotal":50,"reason":"越权调整"}
                """,
                normal.csrf(),
                "normal-key-1"
        ).statusCode()).isEqualTo(403);
    }

    @Test
    void activeAdminCanUseAllSevenEndpointsAndSeeAggregatedDataOnly() throws Exception {
        // 目标用户 T：注册后在注册事务里初始化 FREE 20/10 额度。
        BrowserSession target = new BrowserSession();
        UUID targetId = register(target, "admin-target@example.com");

        // 为 T 造跨用户可聚合的数据：2 个岗位、1 SUCCESS 任务、1 FAILED 任务、1 台 ACTIVE 设备。
        UUID jobOk = UUID.randomUUID();
        UUID jobFail = UUID.randomUUID();
        UUID successTask = UUID.randomUUID();
        UUID failTask = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('a', 64), 'OK岗位', '示例公司',
                    'https://www.zhipin.com/job_detail/ok.html', now(), now())
                """, jobOk, targetId);
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('b', 64), '失败岗位', '示例公司',
                    'https://www.zhipin.com/job_detail/fail.html', now(), now())
                """, jobFail, targetId);
        jdbc.update("""
                INSERT INTO app.delivery_tasks(
                    id, user_id, job_post_id, status, idempotency_key_hash, idempotency_payload_hash,
                    confirmed_at, confirmed_by, finished_at
                ) VALUES (?, ?, ?, 'SUCCESS', repeat('c', 64), repeat('d', 64), now(), ?, now())
                """, successTask, targetId, jobOk, targetId);
        jdbc.update("""
                INSERT INTO app.delivery_tasks(
                    id, user_id, job_post_id, status, idempotency_key_hash, idempotency_payload_hash,
                    confirmed_at, confirmed_by, finished_at, last_error_code, last_error_message
                ) VALUES (?, ?, ?, 'FAILED', repeat('e', 64), repeat('f', 64), now(), ?, now(),
                    'NETWORK_ERROR', '投递失败')
                """, failTask, targetId, jobFail, targetId);
        jdbc.update("""
                INSERT INTO app.plugin_devices(
                    id, user_id, device_name, installation_id_hash, extension_version, capabilities
                ) VALUES (?, ?, '测试设备', repeat('a1', 32), '1.0.0', '["BOSS"]'::jsonb)
                """, device, targetId);

        // 管理员：注册 → 提权 → 重新登录得到 ROLE_ADMIN 会话。
        BrowserSession admin = new BrowserSession();
        UUID adminId = register(admin, "admin-actor@example.com");
        jdbc.update("UPDATE app.users SET role='ADMIN' WHERE id=?", adminId);
        login(admin, "admin-actor@example.com", "StrongPassword!2026");

        // 1) GET /api/admin/users?page=0&size=20
        HttpResponse<String> users = admin.get("/api/admin/users?page=0&size=20");
        assertThat(users.statusCode()).as(users.body()).isEqualTo(200);
        JsonNode usersData = json(users).at("/data");
        assertThat(usersData.at("/total").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode targetRow = null;
        for (JsonNode row : usersData.at("/users")) {
            if (row.at("/id").asText().equals(targetId.toString())) {
                targetRow = row;
            }
        }
        assertThat(targetRow).isNotNull();
        assertThat(targetRow.at("/emailMasked").asText()).endsWith("@example.com");
        assertThat(targetRow.at("/emailMasked").asText()).doesNotContain("admin-target@example.com");
        assertThat(targetRow.at("/analysisQuota/total").asLong()).isEqualTo(20);
        assertThat(targetRow.at("/deliveryQuota/total").asLong()).isEqualTo(10);
        assertThat(targetRow.at("/jobCount").asLong()).isEqualTo(2);
        assertThat(targetRow.at("/deliveryTaskCount").asLong()).isEqualTo(2);
        assertThat(targetRow.at("/successCount").asLong()).isEqualTo(1);
        assertThat(targetRow.at("/failedCount").asLong()).isEqualTo(1);
        assertThat(targetRow.at("/activeDeviceCount").asLong()).isEqualTo(1);
        assertNoSensitiveFields(usersData.at("/users"));

        // 2) GET /api/admin/users/{id}
        HttpResponse<String> detail = admin.get("/api/admin/users/" + targetId);
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(json(detail).at("/data/id").asText()).isEqualTo(targetId.toString());
        assertThat(json(detail).at("/data/plan").asText()).isEqualTo("FREE");
        assertNoSensitiveFields(json(detail).at("/data"));

        // 3) GET /api/admin/users/{id}/quota
        HttpResponse<String> quotaRows = admin.get("/api/admin/users/" + targetId + "/quota");
        assertThat(quotaRows.statusCode()).as(quotaRows.body()).isEqualTo(200);
        JsonNode rows = json(quotaRows).at("/data");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.at("/0/resourceCode").asText()).isEqualTo("AI_ANALYSIS");
        assertThat(rows.at("/0/total").asLong()).isEqualTo(20);
        assertThat(rows.at("/1/resourceCode").asText()).isEqualTo("DELIVERY_CONFIRM");
        assertThat(rows.at("/1/total").asLong()).isEqualTo(10);

        // 4) PUT /api/admin/users/{id}/quota → OK 并写 ADJUST 流水与 ADMIN 审计。
        HttpResponse<String> adjust = admin.put(
                "/api/admin/users/" + targetId + "/quota",
                """
                {"plan":"PREMIUM_MONTHLY","analysisQuotaTotal":100,"deliveryQuotaTotal":50,"reason":"运营调整"}
                """,
                admin.csrf(),
                "admin-put-key-1"
        );
        assertThat(adjust.statusCode()).as(adjust.body()).isEqualTo(200);
        JsonNode adjustData = json(adjust).at("/data");
        assertThat(adjustData.at("/plan").asText()).isEqualTo("PREMIUM_MONTHLY");
        assertThat(adjustData.at("/analysisQuota/total").asLong()).isEqualTo(100);
        assertThat(adjustData.at("/deliveryQuota/total").asLong()).isEqualTo(50);
        Integer adjustLogs = jdbc.queryForObject(
                "SELECT count(*) FROM app.quota_usage_logs WHERE user_id=? AND action='ADJUST'",
                Integer.class, targetId
        );
        assertThat(adjustLogs).isEqualTo(2);
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE action='ADMIN_QUOTA_ADJUSTED' " +
                        "AND actor_type='ADMIN' AND actor_id=? AND user_id=? AND target_id=?",
                Integer.class, adminId, targetId, targetId
        );
        assertThat(auditCount).isEqualTo(1);
        assertNoSensitiveFields(adjustData);

        // 同一 PUT 幂等键 replay：额度流水不重复。
        HttpResponse<String> replay = admin.put(
                "/api/admin/users/" + targetId + "/quota",
                """
                {"plan":"PREMIUM_MONTHLY","analysisQuotaTotal":100,"deliveryQuotaTotal":50,"reason":"运营调整"}
                """,
                admin.csrf(),
                "admin-put-key-1"
        );
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.quota_usage_logs WHERE user_id=? AND action='ADJUST'",
                Integer.class, targetId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE action='ADMIN_QUOTA_ADJUSTED' AND actor_id=? AND user_id=?",
                Integer.class, adminId, targetId
        )).isEqualTo(1);

        // 5) GET /api/admin/dashboard
        HttpResponse<String> dashboard = admin.get("/api/admin/dashboard");
        assertThat(dashboard.statusCode()).as(dashboard.body()).isEqualTo(200);
        JsonNode dash = json(dashboard).at("/data");
        assertThat(dash.at("/totalUsers").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(dash.at("/jobs").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(dash.at("/deliveryTasks").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(dash.at("/failedCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dash.at("/activeDevices").asLong()).isGreaterThanOrEqualTo(1);
        assertNoSensitiveFields(dash);

        // 6) GET /api/admin/audit-logs 能看到刚写入的 ADMIN_QUOTA_ADJUSTED。
        HttpResponse<String> auditLogs = admin.get("/api/admin/audit-logs?limit=50");
        assertThat(auditLogs.statusCode()).as(auditLogs.body()).isEqualTo(200);
        JsonNode auditRows = json(auditLogs).at("/data");
        assertThat(auditRows.size()).isGreaterThanOrEqualTo(1);
        boolean sawAdjust = false;
        for (JsonNode row : auditRows) {
            if ("ADMIN_QUOTA_ADJUSTED".equals(row.at("/action").asText())
                    && row.at("/targetId").asText().equals(targetId.toString())) {
                sawAdjust = true;
                assertThat(row.at("/actorType").asText()).isEqualTo("ADMIN");
                assertThat(row.at("/userId").asText()).isEqualTo(targetId.toString());
                assertThat(row.at("/userEmailMasked").asText()).endsWith("@example.com");
                assertThat(row.at("/userEmailMasked").asText()).doesNotContain("admin-target@example.com");
            }
        }
        assertThat(sawAdjust).isTrue();
        assertNoSensitiveFields(auditRows);

        // 7) GET /api/admin/delivery-failures 能看到 T 的 FAILED 任务且不泄露完整邮箱。
        HttpResponse<String> failures = admin.get("/api/admin/delivery-failures?limit=50");
        assertThat(failures.statusCode()).as(failures.body()).isEqualTo(200);
        JsonNode failureRows = json(failures).at("/data");
        boolean sawFail = false;
        for (JsonNode row : failureRows) {
            if (row.at("/taskId").asText().equals(failTask.toString())) {
                sawFail = true;
                assertThat(row.at("/userId").asText()).isEqualTo(targetId.toString());
                assertThat(row.at("/lastErrorCode").asText()).isEqualTo("NETWORK_ERROR");
                assertThat(row.at("/emailMasked").asText()).doesNotContain("admin-target@example.com");
            }
        }
        assertThat(sawFail).isTrue();
        assertNoSensitiveFields(failureRows);
    }

    @Test
    void quotaAdjustRejectsBelowUsageAndUnknownTargets() throws Exception {
        BrowserSession target = new BrowserSession();
        UUID targetId = register(target, "admin-below@example.com");
        // 用掉 15 次 AI 分析额度。
        jdbc.update("""
                UPDATE app.user_quotas SET used_amount=15, version=version+1
                WHERE user_id=? AND resource_code='AI_ANALYSIS'
                """, targetId);

        BrowserSession admin = new BrowserSession();
        UUID adminId = register(admin, "admin-below-actor@example.com");
        jdbc.update("UPDATE app.users SET role='ADMIN' WHERE id=?", adminId);
        login(admin, "admin-below-actor@example.com", "StrongPassword!2026");

        // 新总量低于 used+reserved → 422 QUOTA_BELOW_USAGE 且额度不变。
        HttpResponse<String> below = admin.put(
                "/api/admin/users/" + targetId + "/quota",
                """
                {"plan":"MONTHLY","analysisQuotaTotal":5,"deliveryQuotaTotal":50,"reason":"过低额度"}
                """,
                admin.csrf(),
                "below-key-1"
        );
        assertThat(below.statusCode()).as(below.body()).isEqualTo(422);
        assertThat(json(below).at("/error/code").asText()).isEqualTo("QUOTA_BELOW_USAGE");
        assertThat(jdbc.queryForObject(
                "SELECT limit_amount FROM app.user_quotas WHERE user_id=? AND resource_code='AI_ANALYSIS'",
                Integer.class, targetId
        )).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.quota_usage_logs WHERE user_id=? AND action='ADJUST'",
                Integer.class, targetId
        )).isZero();

        // 不存在/已删除用户 → 404，且不泄露枚举细节。
        UUID missing = UUID.randomUUID();
        assertThat(admin.get("/api/admin/users/" + missing).statusCode()).isEqualTo(404);
        assertThat(admin.get("/api/admin/users/" + missing + "/quota").statusCode()).isEqualTo(404);
        HttpResponse<String> missingPut = admin.put(
                "/api/admin/users/" + missing + "/quota",
                """
                {"plan":"MONTHLY","analysisQuotaTotal":100,"deliveryQuotaTotal":50,"reason":"目标不存在"}
                """,
                admin.csrf(),
                "missing-key-1"
        );
        assertThat(missingPut.statusCode()).isEqualTo(404);
    }

    @Test
    void lockedAdminAccountIsRejected() throws Exception {
        BrowserSession admin = new BrowserSession();
        UUID adminId = register(admin, "admin-locked@example.com");
        jdbc.update("UPDATE app.users SET role='ADMIN' WHERE id=?", adminId);
        login(admin, "admin-locked@example.com", "StrongPassword!2026");
        assertThat(admin.get("/api/admin/dashboard").statusCode()).isEqualTo(200);

        // 登录后把管理员账号锁住：下一个请求必须被拒绝。
        jdbc.update("UPDATE app.users SET status='LOCKED', locked_until=now() + interval '1 hour' WHERE id=?", adminId);
        HttpResponse<String> locked = admin.get("/api/admin/dashboard");
        assertThat(locked.statusCode()).isEqualTo(403);
        assertThat(json(locked).at("/error/code").asText()).isEqualTo("ACCOUNT_LOCKED");
    }

    /** 断言数据节点及其子孙字段绝不包含敏感字段名。 */
    private void assertNoSensitiveFields(JsonNode dataNode) throws Exception {
        String serialized = objectMapper.writeValueAsString(dataNode);
        assertThat(serialized)
                .doesNotContain("password", "passwordHash", "token", "apiKey", "cookie")
                .doesNotContain("ipHash", "requestId", "details", "request_id");
    }

    private UUID register(BrowserSession browser, String email) throws Exception {
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"%s","password":"StrongPassword!2026","acceptTerms":true}
                """.formatted(email),
                browser.csrf()
        );
        assertThat(registration.statusCode()).as(registration.body()).isEqualTo(201);
        return UUID.fromString(json(registration).at("/data/user/id").asText());
    }

    private void login(BrowserSession browser, String email, String password) throws Exception {
        HttpResponse<String> response = browser.post(
                "/api/auth/login",
                """
                {"email":"%s","password":"%s","rememberMe":false}
                """.formatted(email, password),
                browser.csrf()
        );
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (csrfToken != null) {
                builder.header("X-CSRF-TOKEN", csrfToken);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> put(String path, String body, String csrfToken, String idempotencyKey) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", idempotencyKey)
                            .header("X-CSRF-TOKEN", csrfToken)
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }
}
