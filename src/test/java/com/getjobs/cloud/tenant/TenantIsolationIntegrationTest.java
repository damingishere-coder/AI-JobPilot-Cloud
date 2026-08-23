package com.getjobs.cloud.tenant;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨租户隔离的端到端验证：应用以 RLS 强制角色 {@code jobpilot_app} 运行
 * （迁移由 owner 角色执行），因此以下断言同时覆盖服务层所有权校验与
 * PostgreSQL 行级安全：
 * <ul>
 *   <li>A 不能读取/修改/删除 B 的简历（含提取文本获取路径）；</li>
 *   <li>A 不能读取 B 的岗位、AI 匹配结果与投递任务，统一 404 且不泄露资源 ID；</li>
 *   <li>数据库层：A 的租户上下文看不到 B 的任何行，无租户上下文看不到任何行。</li>
 * </ul>
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
                "app.auth.hash-pepper=tenant-isolation-test-pepper-at-least-32-bytes",
                "app.auth.login-ip-limit=100",
                "app.auth.login-email-limit=100",
                "app.auth.register-ip-limit=100",
                "app.auth.csrf-ip-limit=100",
                "app.rate-limit.match-analyze-limit=100",
                "app.rate-limit.match-batch-limit=100",
                "app.rate-limit.resume-upload-limit=100",
                "app.ai-match.outbox-poll-delay=365d"
        }
)
class TenantIsolationIntegrationTest {
    private static final String APP_ROLE = "jobpilot_app";
    private static final String APP_PASSWORD = "tenant-isolation-app-password";
    private static final String REDIS_PASSWORD = "tenant_isolation_redis_password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-tenant-" + UUID.randomUUID()
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
    static void infrastructureProperties(DynamicPropertyRegistry registry) throws Exception {
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); Statement statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE ai_jobpilot TO " + APP_ROLE);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // 迁移始终由 owner 角色执行，运行时才是受限的 jobpilot_app。
        registry.add("spring.flyway.user", POSTGRES::getUsername);
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
    JdbcTemplate jdbc;

    @Test
    void crossTenantReadsUpdatesAndDeletesAreRejectedWithoutLeakingResourceIds() throws Exception {
        Tenant tenantA = register("tenant-a@example.com");
        Tenant tenantB = register("tenant-b@example.com");
        BrowserSession userA = tenantA.browser();
        UUID userIdA = tenantA.userId();
        UUID userIdB = tenantB.userId();
        String csrfA = csrfOf(userA);

        // Seed user B's full data set via the owner role.
        UUID resumeB = UUID.randomUUID();
        UUID preferenceB = UUID.randomUUID();
        UUID jobB = UUID.randomUUID();
        UUID matchB = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'B的简历.pdf', 'objects/b-resume', 'application/pdf', 4096,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeB, userIdB));
            statement.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles)
                    VALUES ('%s', '%s', 1, '["产品经理"]'::jsonb)
                    """.formatted(preferenceB, userIdB));
            statement.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'B的目标岗位', 'B的公司',
                        'https://www.zhipin.com/job_detail/b.html', now(), now()
                    )
                    """.formatted(jobB, userIdB));
            statement.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        input_fingerprint, score, decision, summary, completed_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'SUCCEEDED', repeat('d', 64),
                        88, 'APPLY', 'B的AI匹配结论', now()
                    )
                    """.formatted(matchB, userIdB, jobB, resumeB, preferenceB));
            statement.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, job_match_id, status,
                        idempotency_key_hash, idempotency_payload_hash
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'WAITING_CONFIRM',
                        repeat('e', 64), repeat('f', 64)
                    )
                    """.formatted(taskB, userIdB, jobB, matchB));
        }

        // A 的简历列表/当前简历/提取文本读取都看不到 B 的数据。
        HttpResponse<String> list = userA.get("/api/resumes");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(json(list).at("/data/total").asLong()).isZero();
        assertThat(json(list).at("/data/items").size()).isZero();
        HttpResponse<String> current = userA.get("/api/resumes/current?includeExtractedText=true");
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
        assertThat(json(current).get("data").isNull()).isTrue();
        assertThat(current.body()).doesNotContain("B的简历.pdf", resumeB.toString());

        // A 不能删除 B 的简历：统一 404，且响应不携带 B 的资源 ID。
        HttpResponse<String> delete = userA.requestWithHeaders(
                "DELETE", "/api/resumes/" + resumeB, "", csrfA,
                Map.of("If-Match", "1", "Idempotency-Key", "tenant-delete-" + UUID.randomUUID())
        );
        assertThat(delete.statusCode()).as(delete.body()).isEqualTo(404);
        assertThat(json(delete).at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(delete.body()).doesNotContain(resumeB.toString());

        // A 不能读取 B 的岗位与 AI 匹配结果。
        HttpResponse<String> jobDetail = userA.get("/api/jobs/" + jobB);
        assertThat(jobDetail.statusCode()).as(jobDetail.body()).isEqualTo(404);
        assertThat(jobDetail.body()).doesNotContain(jobB.toString(), "B的目标岗位");
        HttpResponse<String> analyze = userA.postWithHeaders(
                "/api/jobs/" + jobB + "/analyze", "{}", csrfA,
                Map.of("Idempotency-Key", "tenant-analyze-" + UUID.randomUUID())
        );
        assertThat(analyze.statusCode()).as(analyze.body()).isEqualTo(404);
        assertThat(json(analyze).at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        HttpResponse<String> match = userA.get("/api/jobs/" + jobB + "/match");
        assertThat(match.statusCode()).as(match.body()).isEqualTo(404);
        assertThat(json(match).at("/error/code").asText()).isEqualTo("MATCH_NOT_FOUND");
        assertThat(match.body()).doesNotContain(matchB.toString(), "B的AI匹配结论");

        // A 不能读取/确认/跳过 B 的投递任务。
        HttpResponse<String> taskDetail = userA.get("/api/delivery/tasks/" + taskB);
        assertThat(taskDetail.statusCode()).as(taskDetail.body()).isEqualTo(404);
        assertThat(taskDetail.body()).doesNotContain(taskB.toString());
        HttpResponse<String> confirm = userA.postWithHeaders(
                "/api/delivery/tasks/" + taskB + "/confirm",
                "{\"version\":1,\"acknowledged\":true}", csrfA,
                Map.of("Idempotency-Key", "tenant-confirm-" + UUID.randomUUID())
        );
        assertThat(confirm.statusCode()).as(confirm.body()).isEqualTo(404);
        HttpResponse<String> skip = userA.postWithHeaders(
                "/api/delivery/tasks/" + taskB + "/skip",
                "{\"version\":1,\"reason\":\"不感兴趣\"}", csrfA,
                Map.of("Idempotency-Key", "tenant-skip-" + UUID.randomUUID())
        );
        assertThat(skip.statusCode()).as(skip.body()).isEqualTo(404);

        // A 的求职目标读取不受 B 数据影响。
        HttpResponse<String> preference = userA.get("/api/preferences");
        assertThat(preference.statusCode()).as(preference.body()).isEqualTo(200);
        assertThat(json(preference).get("data").isNull()).isTrue();

        // B 的数据在全部越权尝试后保持原样。
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.resumes WHERE id='" + resumeB + "' AND is_current"
            ))).isEqualTo(1);
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.job_posts WHERE id='" + jobB + "'"
            ))).isEqualTo(1);
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.job_matches WHERE id='" + matchB + "' AND status='SUCCEEDED'"
            ))).isEqualTo(1);
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE id='" + taskB + "'"
            ))).isEqualTo(1);
        }

        // A 的审计日志没有记录任何引用 B 资源 ID 的事件（审计表只允许
        // SECURITY DEFINER 函数写入，app 角色无读取权限，故经 owner 校验）。
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            var rows = statement.executeQuery(
                    "SELECT coalesce(string_agg(coalesce(details::text, ''), ' '), '') "
                            + "FROM app.audit_logs WHERE actor_id = '" + userIdA + "'"
            );
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1))
                    .doesNotContain(resumeB.toString(), jobB.toString(), matchB.toString(), taskB.toString());
        }
    }

    @Test
    void databaseRlsHidesForeignRowsAndRequiresTenantContext() throws Exception {
        UUID userIdA = register("tenant-rls-a@example.com").userId();
        UUID userIdB = UUID.randomUUID();
        UUID resumeB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES " +
                    "('" + userIdB + "', 'tenant-rls-b@example.com', '$argon2id$test')");
            statement.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'B-resume.txt', 'objects/b', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeB, userIdB));
        }

        try (Connection app = appConnection(); Statement statement = app.createStatement()) {
            // 无租户上下文：看不到任何受 RLS 保护的行。
            assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.resumes"))).isZero();
            assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.job_posts"))).isZero();
            assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.job_matches"))).isZero();
            assertThat(singleLong(statement.executeQuery("SELECT count(*) FROM app.delivery_tasks"))).isZero();

            // A 的上下文：看不到 B 的简历，也无法更新/删除。
            app.setAutoCommit(false);
            try (Statement tx = app.createStatement()) {
                tx.execute("SELECT set_config('app.current_user_id', '" + userIdA + "', true)");
                assertThat(singleLong(tx.executeQuery(
                        "SELECT count(*) FROM app.resumes WHERE id='" + resumeB + "'"
                ))).isZero();
                assertThat(tx.executeUpdate(
                        "UPDATE app.resumes SET original_filename='tampered' WHERE id='" + resumeB + "'"
                )).isZero();
                assertThat(tx.executeUpdate(
                        "DELETE FROM app.resumes WHERE id='" + resumeB + "'"
                )).isZero();
            }
            app.rollback();

            // B 的上下文：可以看见自己的行。
            app.setAutoCommit(false);
            try (Statement tx = app.createStatement()) {
                tx.execute("SELECT set_config('app.current_user_id', '" + userIdB + "', true)");
                assertThat(singleLong(tx.executeQuery(
                        "SELECT count(*) FROM app.resumes WHERE id='" + resumeB + "'"
                ))).isEqualTo(1);
            }
            app.rollback();
        }

        // A 通过 API 注册产生的默认 profile 写入成功（写入路径在 RLS 下完成）。
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.user_profiles WHERE user_id='" + userIdA + "'"
            ))).isEqualTo(1);
        }
    }

    // ---- helpers ----

    private record Tenant(BrowserSession browser, UUID userId) {
    }

    private Tenant register(String email) throws Exception {
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"%s","password":"StrongPassword!2026","acceptTerms":true}
                """.formatted(email),
                browser.csrf()
        );
        assertThat(registration.statusCode()).as(registration.body()).isEqualTo(201);
        return new Tenant(browser, UUID.fromString(json(registration).at("/data/user/id").asText()));
    }

    private String csrfOf(BrowserSession browser) throws Exception {
        return json(browser.get("/api/auth/csrf")).at("/data/csrfToken").asText();
    }

    private Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection appConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    private static long singleLong(ResultSet resultSet) throws Exception {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
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
            return requestWithHeaders("POST", path, body, csrfToken, Map.of());
        }

        HttpResponse<String> postWithHeaders(String path, String body, String csrfToken,
                                             Map<String, String> extraHeaders) throws Exception {
            return requestWithHeaders("POST", path, body, csrfToken, extraHeaders);
        }

        HttpResponse<String> requestWithHeaders(String method, String path, String body,
                                                String csrfToken, Map<String, String> extraHeaders)
                throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (csrfToken != null) {
                builder.header("X-CSRF-TOKEN", csrfToken);
            }
            extraHeaders.forEach(builder::header);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }
}
