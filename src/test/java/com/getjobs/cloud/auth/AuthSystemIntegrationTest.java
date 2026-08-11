package com.getjobs.cloud.auth;

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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                "app.auth.hash-pepper=integration-test-auth-pepper-at-least-32-bytes",
                "app.auth.login-ip-limit=100",
                "app.auth.login-email-limit=100",
                "app.auth.register-ip-limit=100",
                "app.auth.csrf-ip-limit=100"
        }
)
class AuthSystemIntegrationTest {
    private static final String REDIS_PASSWORD = "integration_auth_redis_password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-auth-" + UUID.randomUUID()
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
    void registerLoginMeAndLogoutUseRotatedRedisSessionAndArgon2Hash() throws Exception {
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> csrfResponse = browser.get("/api/auth/csrf");
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        String anonymousSession = sessionCookie(csrfResponse);
        String csrf = json(csrfResponse).at("/data/csrfToken").asText();

        String email = "Round3.User+One@Example.com";
        String normalizedEmail = "round3.user+one@example.com";
        HttpResponse<String> register = browser.post(
                "/api/auth/register",
                """
                {"email":"%s","password":"StrongPassword!2026","acceptTerms":true}
                """.formatted(email),
                csrf
        );
        assertThat(register.statusCode()).isEqualTo(201);
        assertThat(json(register).at("/data/user/role").asText()).isEqualTo("USER");
        assertThat(json(register).at("/data/user/status").asText()).isEqualTo("ACTIVE");
        assertThat(register.body()).doesNotContain(normalizedEmail, "StrongPassword!2026");
        assertThat(sessionCookie(register)).isNotEqualTo(anonymousSession);
        assertThat(register.headers().allValues("set-cookie").toString())
                .contains("AJP_SESSION=", "HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Max-Age=2592000");
        assertThat(Duration.between(
                Instant.now(),
                Instant.parse(json(register).at("/data/session/expiresAt").asText())
        ).toSeconds()).isBetween(43_190L, 43_210L);

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM app.users WHERE email = ?::citext",
                String.class,
                normalizedEmail
        );
        assertThat(storedHash).startsWith("$argon2").doesNotContain("StrongPassword!2026");

        HttpResponse<String> me = browser.get("/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).at("/data/emailMasked").asText()).isEqualTo("ro***@example.com");
        assertThat(json(me).at("/data/profile/timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(json(me).at("/data/quotaSummary").isArray()).isTrue();
        HttpResponse<String> injectedUserId = browser.get("/api/me?user_id=" + UUID.randomUUID());
        assertThat(injectedUserId.statusCode()).isEqualTo(400);
        assertThat(json(injectedUserId).at("/error/code").asText()).isEqualTo("USER_ID_NOT_ALLOWED");

        String authenticatedCsrf = json(register).at("/data/csrfToken").asText();
        HttpResponse<String> logout = browser.post("/api/auth/logout", "", authenticatedCsrf);
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(200);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(browser.post("/api/auth/logout", "", null).statusCode()).isEqualTo(200);

        String loginCsrf = browser.csrf();
        String loginAnonymousSession = browser.cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("AJP_SESSION"))
                .findFirst()
                .orElseThrow()
                .getValue();
        HttpResponse<String> login = browser.post(
                "/api/auth/login",
                """
                {"email":"%s","password":"StrongPassword!2026","rememberMe":true}
                """.formatted(normalizedEmail),
                loginCsrf
        );
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(sessionCookie(login)).isNotEqualTo("AJP_SESSION=" + loginAnonymousSession);
        assertThat(json(login).at("/data/csrfToken").asText()).isNotEqualTo(loginCsrf);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("Max-Age=2592000");
        assertThat(Duration.between(
                Instant.now(),
                Instant.parse(json(login).at("/data/session/expiresAt").asText())
        ).toSeconds()).isBetween(2_591_990L, 2_592_010L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE action IN ('AUTH_REGISTER', 'AUTH_LOGIN', 'AUTH_LOGOUT')",
                Long.class
        )).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void csrfValidationUnknownFieldsLockoutAndDisabledAccountAreRejected() throws Exception {
        BrowserSession noCsrf = new BrowserSession();
        assertThat(noCsrf.csrf()).isNotBlank();
        assertThat(noCsrf.post("/api/auth/logout", "", null).statusCode()).isEqualTo(200);
        assertThat(noCsrf.post(
                "/api/auth/login",
                "{\"email\":\"none@example.com\",\"password\":\"StrongPassword!2026\",\"rememberMe\":false}",
                null
        ).statusCode()).isEqualTo(403);

        BrowserSession unknownField = new BrowserSession();
        String unknownCsrf = unknownField.csrf();
        HttpResponse<String> malformed = unknownField.post(
                "/api/auth/register",
                """
                {"email":"fields@example.com","password":"StrongPassword!2026","acceptTerms":true,"user_id":"forbidden"}
                """,
                unknownCsrf
        );
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).at("/error/code").asText()).isEqualTo("MALFORMED_JSON");

        BrowserSession activeSession = new BrowserSession();
        String registrationCsrf = activeSession.csrf();
        HttpResponse<String> registration = activeSession.post(
                "/api/auth/register",
                """
                {"email":"locked@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                registrationCsrf
        );
        assertThat(registration.statusCode()).isEqualTo(201);
        BrowserSession attacker = new BrowserSession();
        String failedLoginCsrf = attacker.csrf();
        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<String> failed = attacker.post(
                    "/api/auth/login",
                    """
                    {"email":"locked@example.com","password":"WrongPassword!2026","rememberMe":false}
                    """,
                    failedLoginCsrf
            );
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(attempt < 5 ? 401 : 423);
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.users WHERE email='locked@example.com'::citext",
                String.class
        )).isEqualTo("LOCKED");
        assertThat(activeSession.get("/api/me").statusCode()).isEqualTo(401);

        jdbc.update(
                "UPDATE app.users SET locked_until = now() - interval '1 second' " +
                        "WHERE email='locked@example.com'::citext"
        );
        BrowserSession automaticallyUnlocked = new BrowserSession();
        HttpResponse<String> unlockedLogin = automaticallyUnlocked.post(
                "/api/auth/login",
                """
                {"email":"locked@example.com","password":"StrongPassword!2026","rememberMe":false}
                """,
                automaticallyUnlocked.csrf()
        );
        assertThat(unlockedLogin.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT failed_login_count FROM app.users WHERE email='locked@example.com'::citext",
                Integer.class
        )).isZero();
        automaticallyUnlocked.post(
                "/api/auth/logout",
                "",
                json(unlockedLogin).at("/data/csrfToken").asText()
        );

        jdbc.update(
                "UPDATE app.users SET status='ACTIVE', failed_login_count=5, locked_until=NULL " +
                        "WHERE email='locked@example.com'::citext"
        );
        BrowserSession escalating = new BrowserSession();
        String escalatingCsrf = escalating.csrf();
        assertThat(escalating.post(
                "/api/auth/login",
                """
                {"email":"locked@example.com","password":"WrongPassword!2026","rememberMe":false}
                """,
                escalatingCsrf
        ).statusCode()).isEqualTo(423);
        assertThat(jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (locked_until - now())) FROM app.users " +
                        "WHERE email='locked@example.com'::citext",
                Double.class
        )).isBetween(29 * 60.0, 30 * 60.0);

        jdbc.update(
                "UPDATE app.users SET status='ACTIVE', failed_login_count=11, locked_until=NULL " +
                        "WHERE email='locked@example.com'::citext"
        );
        assertThat(escalating.post(
                "/api/auth/login",
                """
                {"email":"locked@example.com","password":"WrongPassword!2026","rememberMe":false}
                """,
                escalatingCsrf
        ).statusCode()).isEqualTo(423);
        assertThat(jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (locked_until - now())) FROM app.users " +
                        "WHERE email='locked@example.com'::citext",
                Double.class
        )).isBetween(23 * 60 * 60.0, 24 * 60 * 60.0);

        BrowserSession disabled = new BrowserSession();
        HttpResponse<String> disabledRegistration = disabled.post(
                "/api/auth/register",
                """
                {"email":"disabled@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                disabled.csrf()
        );
        assertThat(disabledRegistration.statusCode()).isEqualTo(201);
        jdbc.update("UPDATE app.users SET status='DISABLED' WHERE email='disabled@example.com'::citext");
        assertThat(disabled.get("/api/me").statusCode()).isEqualTo(403);

        BrowserSession disabledLogin = new BrowserSession();
        HttpResponse<String> denied = disabledLogin.post(
                "/api/auth/login",
                """
                {"email":"disabled@example.com","password":"StrongPassword!2026","rememberMe":false}
                """,
                disabledLogin.csrf()
        );
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).at("/error/code").asText()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void duplicateEmailWeakPasswordAndAuditDataDoNotLeakSensitiveValues() throws Exception {
        BrowserSession first = new BrowserSession();
        HttpResponse<String> created = first.post(
                "/api/auth/register",
                """
                {"email":"privacy@example.com","password":"UnrelatedStrong!2026","acceptTerms":true}
                """,
                first.csrf()
        );
        assertThat(created.statusCode()).isEqualTo(201);

        BrowserSession duplicate = new BrowserSession();
        HttpResponse<String> conflict = duplicate.post(
                "/api/auth/register",
                """
                {"email":" PRIVACY@example.com ","password":"AnotherStrong!2026","acceptTerms":true}
                """,
                duplicate.csrf()
        );
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).at("/error/code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(conflict.body()).doesNotContain("privacy@example.com", "AnotherStrong!2026");

        BrowserSession weak = new BrowserSession();
        HttpResponse<String> validation = weak.post(
                "/api/auth/register",
                """
                {"email":"similar.person@example.com","password":"SimilarPerson!2026","acceptTerms":true}
                """,
                weak.csrf()
        );
        assertThat(validation.statusCode()).isEqualTo(400);
        assertThat(json(validation).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");

        String auditText = jdbc.queryForObject(
                """
                SELECT coalesce(string_agg(
                    coalesce(details::text, '') || ' ' || coalesce(ip_hash, '') || ' '
                    || coalesce(user_agent_summary, ''), ' '
                ), '')
                FROM app.audit_logs
                """,
                String.class
        );
        assertThat(auditText)
                .doesNotContain("privacy@example.com", "UnrelatedStrong!2026", "127.0.0.1")
                .doesNotContain(json(created).at("/data/csrfToken").asText());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("AJP_SESSION="))
                .map(value -> value.substring(0, value.indexOf(';')))
                .findFirst()
                .orElseThrow();
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
