package com.getjobs.cloud.quota;

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
 * GET /api/quota/me 的端到端鉴权与仅本人返回测试：
 * 未登录 401；注册后返回本人 plan/resetCycle 与两资源额度，且不泄漏其他用户或流水键。
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
                "app.auth.hash-pepper=integration-quota-test-pepper-at-least-32-bytes",
                "app.auth.login-ip-limit=100",
                "app.auth.login-email-limit=100",
                "app.auth.register-ip-limit=100",
                "app.auth.csrf-ip-limit=100",
                "app.ai-match.outbox-poll-delay=365d"
        }
)
class QuotaControllerIntegrationTest {
    private static final String REDIS_PASSWORD = "integration_quota_redis_password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-quota-" + UUID.randomUUID()
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
    void quotaMeRequiresLoginAndReturnsOnlyOwnMonthlyQuota() throws Exception {
        // 未登录访问 /api/quota/me → 401 AUTH_REQUIRED。
        BrowserSession anonymous = new BrowserSession();
        HttpResponse<String> denied = anonymous.get("/api/quota/me");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(json(denied).at("/error/code").asText()).isEqualTo("AUTH_REQUIRED");

        // 注册后（注册事务应已初始化 FREE 20/10）。
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"quota-me@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                browser.csrf()
        );
        assertThat(registration.statusCode()).isEqualTo(201);
        UUID userId = UUID.fromString(json(registration).at("/data/user/id").asText());

        // GET /api/quota/me（GET 不要求 CSRF，但要求会话）返回本人 FREE 月度额度。
        HttpResponse<String> me = browser.get("/api/quota/me");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        JsonNode data = json(me).at("/data");
        assertThat(data.at("/plan").asText()).isEqualTo("FREE");
        assertThat(data.at("/resetCycle").asText()).isEqualTo("MONTHLY");
        assertThat(data.at("/resetAt").isTextual()).isTrue();
        assertThat(data.at("/resources").size()).isEqualTo(2);
        assertThat(data.at("/resources/0/resourceCode").asText()).isEqualTo("AI_ANALYSIS");
        assertThat(data.at("/resources/0/total").asLong()).isEqualTo(20);
        assertThat(data.at("/resources/0/used").asLong()).isZero();
        assertThat(data.at("/resources/0/remaining").asLong()).isEqualTo(20);
        assertThat(data.at("/resources/1/resourceCode").asText()).isEqualTo("DELIVERY_CONFIRM");
        assertThat(data.at("/resources/1/total").asLong()).isEqualTo(10);
        assertThat(data.at("/resources/1/remaining").asLong()).isEqualTo(10);

        // 响应不得包含流水幂等键、其他用户或敏感字段。
        assertThat(me.body())
                .doesNotContain("operationKey", "quotaId", "reservationId")
                .doesNotContain("password", "hash", "emailMasked");

        // 消费一次投递额度后，接口反映 used/remaining，且只影响本人。
        jdbc.update("""
                INSERT INTO app.quota_usage_logs(
                    user_id, quota_id, resource_code, action, amount, reference_type,
                    reference_id, operation_key, reason, balance_after
                )
                SELECT user_id, id, resource_code, 'COMMIT', 1, 'DELIVERY_TASK',
                       gen_random_uuid(), 'quota-me:delivery:1', '端到端确认消耗', 1
                FROM app.user_quotas
                WHERE user_id = ? AND resource_code = 'DELIVERY_CONFIRM'
                """, userId);
        jdbc.update("""
                UPDATE app.user_quotas
                SET used_amount = used_amount + 1, version = version + 1
                WHERE user_id = ? AND resource_code = 'DELIVERY_CONFIRM'
                """, userId);
        HttpResponse<String> updated = browser.get("/api/quota/me");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).at("/data/resources/1/used").asLong()).isEqualTo(1);
        assertThat(json(updated).at("/data/resources/1/remaining").asLong()).isEqualTo(9);
        assertThat(json(updated).at("/data/resources/0/used").asLong()).isZero();
    }

    @Test
    void secondUserSeesOnlyItsOwnZeroUsageQuota() throws Exception {
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"quota-me-other@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                browser.csrf()
        );
        assertThat(registration.statusCode()).isEqualTo(201);

        HttpResponse<String> me = browser.get("/api/quota/me");
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode data = json(me).at("/data");
        assertThat(data.at("/resources/0/used").asLong()).isZero();
        assertThat(data.at("/resources/1/used").asLong()).isZero();
        // 其他用户的消耗（前一个测试写入的 delivery=1）不可见。
        assertThat(data.at("/resources/1/total").asLong()).isEqualTo(10);
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

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }
}
