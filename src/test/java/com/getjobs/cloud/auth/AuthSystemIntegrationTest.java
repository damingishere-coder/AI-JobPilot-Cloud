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
import java.util.Map;
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
                "app.auth.csrf-ip-limit=100",
                // Keep the API profile's scheduled outbox publisher away from the
                // outbox rows asserted in this test: a long poll delay disables it.
                "app.ai-match.outbox-poll-delay=365d"
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

    @Test
    void preferencesAndJobPoolValidateVersionsFiltersAndTenantOwnership() throws Exception {
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"round4-api@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                browser.csrf()
        );
        assertThat(registration.statusCode()).isEqualTo(201);
        String csrf = json(registration).at("/data/csrfToken").asText();
        UUID userA = UUID.fromString(json(registration).at("/data/user/id").asText());
        UUID userB = UUID.randomUUID();

        HttpResponse<String> emptyPreference = browser.get("/api/preferences");
        assertThat(emptyPreference.statusCode()).isEqualTo(200);
        assertThat(json(emptyPreference).get("data").isNull()).isTrue();

        HttpResponse<String> preference = browser.put(
                "/api/preferences",
                """
                {
                  "version": null,
                  "targetTitles": [" Java 开发 ", "java 开发", "后端开发"],
                  "cities": ["上海"],
                  "salaryMinK": 20,
                  "salaryMaxK": 35,
                  "experienceLevels": [],
                  "degreeLevels": [],
                  "industries": [],
                  "companyScales": [],
                  "preferredCompanies": [],
                  "excludedCompanies": [],
                  "excludedKeywords": [],
                  "extraFilters": {}
                }
                """,
                csrf
        );
        assertThat(preference.statusCode()).as(preference.body()).isEqualTo(200);
        assertThat(json(preference).at("/data/version").asInt()).isEqualTo(1);
        assertThat(json(preference).at("/data/targetTitles").size()).isEqualTo(2);

        HttpResponse<String> invalidSalary = browser.put(
                "/api/preferences",
                """
                {
                  "version": 1,
                  "targetTitles": ["Java 开发"],
                  "cities": [],
                  "salaryMinK": 40,
                  "salaryMaxK": 20,
                  "experienceLevels": [],
                  "degreeLevels": [],
                  "industries": [],
                  "companyScales": [],
                  "preferredCompanies": [],
                  "excludedCompanies": [],
                  "excludedKeywords": [],
                  "extraFilters": {}
                }
                """,
                csrf
        );
        assertThat(invalidSalary.statusCode()).isEqualTo(400);
        assertThat(json(invalidSalary).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");

        HttpResponse<String> staleVersion = browser.put(
                "/api/preferences",
                preferenceRequest(null),
                csrf
        );
        assertThat(staleVersion.statusCode()).isEqualTo(409);
        assertThat(json(staleVersion).at("/error/code").asText()).isEqualTo("RESOURCE_VERSION_CONFLICT");

        UUID jobA = UUID.randomUUID();
        UUID jobB = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app.users(id, email, password_hash) VALUES (?, 'round4-other@example.com', '$argon2id$test')",
                userB
        );
        jdbc.update(
                """
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('a', 64), 'Java 后端工程师', 'A 公司',
                          'https://www.zhipin.com/job_detail/a.html', now(), now())
                """,
                jobA, userA
        );
        jdbc.update(
                """
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('b', 64), '不可见岗位', 'B 公司',
                          'https://www.zhipin.com/job_detail/b.html', now(), now())
                """,
                jobB, userB
        );

        HttpResponse<String> jobs = browser.get("/api/jobs?keyword=Java&sort=title,asc");
        assertThat(jobs.statusCode()).as(jobs.body()).isEqualTo(200);
        assertThat(json(jobs).at("/data/total").asLong()).isEqualTo(1);
        assertThat(json(jobs).at("/data/items/0/id").asText()).isEqualTo(jobA.toString());
        assertThat(browser.get("/api/jobs/" + jobB).statusCode()).isEqualTo(404);

        HttpResponse<String> unsafeSort = browser.get("/api/jobs?sort=title;drop%20table%20app.users,asc");
        assertThat(unsafeSort.statusCode()).isEqualTo(400);
        assertThat(json(unsafeSort).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void matchApiValidatesMissingAuthCsrfAndInputAndPreservesPreferenceThresholds() throws Exception {
        // Step 1: Register user
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"round5-match@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                browser.csrf()
        );
        assertThat(registration.statusCode()).isEqualTo(201);
        String csrf = json(registration).at("/data/csrfToken").asText();
        UUID userId = UUID.fromString(json(registration).at("/data/user/id").asText());

        // Step 2: Missing auth → 401
        BrowserSession noAuth = new BrowserSession();
        HttpResponse<String> noAuthResp = noAuth.get("/api/jobs/" + UUID.randomUUID() + "/match");
        assertThat(noAuthResp.statusCode()).isEqualTo(401);

        // Step 3: Missing/faulty Idempotency-Key → 400
        // Empty jobIds should also be 400 (validation)
        HttpResponse<String> batchEmpty = browser.post(
                "/api/jobs/batch-analyze",
                "{\"jobIds\":[],\"force\":false}",
                csrf
        );
        assertThat(batchEmpty.statusCode()).isEqualTo(400);
        assertThat(json(batchEmpty).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");

        // Step 4: No matching job → 404
        HttpResponse<String> noMatch = browser.get("/api/jobs/" + UUID.randomUUID() + "/match");
        assertThat(noMatch.statusCode()).isEqualTo(404);
        assertThat(json(noMatch).at("/error/code").asText()).isEqualTo("MATCH_NOT_FOUND");

        // Step 5: Setup test data for preference threshold test
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.resumes(
                    id, user_id, original_filename, storage_key, content_type, file_size,
                    sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                ) VALUES (?, ?, 'resume.txt', 'objects/test', 'text/plain', 2,
                          repeat('a', 64), repeat('a', 64), 'v1', true, 'PARSED')
                """, resumeId, userId);
        jdbc.update("""
                INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                    review_threshold, priority_apply_threshold, apply_threshold)
                VALUES (?, ?, 1, '["Java工程师"]'::jsonb, 60, 65, 75)
                """, preferenceId, userId);
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('b', 64), 'Java后端工程师', 'A公司',
                          'https://www.zhipin.com/job_detail/a.html', now(), now())
                """, jobId, userId);

        // Step 6: Verify preference threshold defaults
        HttpResponse<String> pref = browser.get("/api/preferences");
        assertThat(json(pref).at("/data/reviewThreshold").asInt()).isEqualTo(60);
        assertThat(json(pref).at("/data/priorityApplyThreshold").asInt()).isEqualTo(65);
        assertThat(json(pref).at("/data/applyThreshold").asInt()).isEqualTo(75);

        // Step 7: Update preference — explicitly set one threshold, others inherit
        HttpResponse<String> updatePref = browser.put(
                "/api/preferences",
                """
                {
                  "version": 1,
                  "targetTitles": ["Java 开发"],
                  "cities": [],
                  "salaryMinK": 20,
                  "salaryMaxK": 35,
                  "experienceLevels": [],
                  "degreeLevels": [],
                  "industries": [],
                  "companyScales": [],
                  "preferredCompanies": [],
                  "excludedCompanies": [],
                  "excludedKeywords": [],
                  "extraFilters": {},
                  "reviewThreshold": 50,
                  "applyThreshold": 80
                }
                """,
                csrf
        );
        assertThat(updatePref.statusCode()).as(updatePref.body()).isEqualTo(200);
        assertThat(json(updatePref).at("/data/reviewThreshold").asInt()).isEqualTo(50);
        // priority should inherit from current (65), apply explicitly set to 80
        // Check: 50 ≤ 65 ≤ 80 ✓
        assertThat(json(updatePref).at("/data/priorityApplyThreshold").asInt()).isEqualTo(65);
        assertThat(json(updatePref).at("/data/applyThreshold").asInt()).isEqualTo(80);
    }

    @Test
    void matchApiEnqueuesReusesForceRequeuesAndFiltersByLatestMatch() throws Exception {
        BrowserSession browser = new BrowserSession();
        HttpResponse<String> registration = browser.post(
                "/api/auth/register",
                """
                {"email":"round5-match-full@example.com","password":"StrongPassword!2026","acceptTerms":true}
                """,
                browser.csrf()
        );
        assertThat(registration.statusCode()).isEqualTo(201);
        String csrf = json(registration).at("/data/csrfToken").asText();
        UUID userId = UUID.fromString(json(registration).at("/data/user/id").asText());

        UUID jobId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('a', 64), 'Java后端工程师', 'A公司',
                          'https://www.zhipin.com/job_detail/a.html', now(), now())
                """, jobId, userId);

        // No resume → 428
        HttpResponse<String> noResume = browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "key-no-resume"));
        assertThat(noResume.statusCode()).as(noResume.body()).isEqualTo(428);
        assertThat(json(noResume).at("/error/code").asText()).isEqualTo("PRECONDITION_FAILED");

        // Parsed current resume but no preference → 428
        jdbc.update("""
                INSERT INTO app.resumes(
                    id, user_id, original_filename, storage_key, content_type, file_size,
                    sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                ) VALUES (?, ?, 'resume.txt', 'objects/test', 'text/plain', 2,
                          repeat('a', 64), repeat('a', 64), 'v1', true, 'PARSED')
                """, resumeId, userId);
        HttpResponse<String> noPreference = browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "key-no-pref"));
        assertThat(noPreference.statusCode()).as(noPreference.body()).isEqualTo(428);
        assertThat(json(noPreference).at("/error/code").asText()).isEqualTo("PRECONDITION_FAILED");

        jdbc.update("""
                INSERT INTO app.job_preferences(id, user_id, version, target_titles,
                    review_threshold, priority_apply_threshold, apply_threshold)
                VALUES (?, ?, 1, '["Java工程师"]'::jsonb, 60, 65, 75)
                """, preferenceId, userId);

        // Missing CSRF → 403
        assertThat(browser.post("/api/jobs/" + jobId + "/analyze", "{}", null).statusCode())
                .isEqualTo(403);

        // Missing / blank / too-long Idempotency-Key → 400
        assertThat(browser.post("/api/jobs/" + jobId + "/analyze", "{}", csrf).statusCode())
                .isEqualTo(400);
        assertThat(browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "   ")
        ).statusCode()).isEqualTo(400);
        assertThat(browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "k".repeat(129))
        ).statusCode()).isEqualTo(400);

        // Cross-user job: analyze → 404 without leaking, GET match → 404 MATCH_NOT_FOUND
        UUID otherUser = UUID.randomUUID();
        UUID otherJob = UUID.randomUUID();
        jdbc.update("INSERT INTO app.users(id, email, password_hash) VALUES " +
                "(?, 'round5-match-other@example.com', '$argon2id$test')", otherUser);
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('b', 64), '不可见岗位', 'B公司',
                          'https://www.zhipin.com/job_detail/b.html', now(), now())
                """, otherJob, otherUser);
        HttpResponse<String> foreignAnalyze = browser.postWithHeaders(
                "/api/jobs/" + otherJob + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "key-foreign"));
        assertThat(foreignAnalyze.statusCode()).isEqualTo(404);
        assertThat(json(foreignAnalyze).at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        HttpResponse<String> foreignMatch = browser.get("/api/jobs/" + otherJob + "/match");
        assertThat(foreignMatch.statusCode()).isEqualTo(404);
        assertThat(json(foreignMatch).at("/error/code").asText()).isEqualTo("MATCH_NOT_FOUND");
        assertThat(foreignMatch.body()).doesNotContain(otherJob.toString());

        // First successful analyze: exactly one Match + one REQUESTED outbox
        HttpResponse<String> first = browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "key-first"));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        UUID matchId = UUID.fromString(json(first).at("/data/matchId").asText());
        assertThat(json(first).at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(json(first).at("/data/reusedExisting").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_matches WHERE user_id=? AND job_post_id=?",
                Long.class, userId, jobId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_match_outbox WHERE job_match_id=?",
                Long.class, matchId
        )).isEqualTo(1);

        // Repeating the same input reuses the same matchId and adds nothing
        HttpResponse<String> repeated = browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{}", csrf,
                Map.of("Idempotency-Key", "key-repeat"));
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(200);
        assertThat(json(repeated).at("/data/matchId").asText()).isEqualTo(matchId.toString());
        assertThat(json(repeated).at("/data/reusedExisting").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_matches WHERE user_id=? AND job_post_id=?",
                Long.class, userId, jobId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_match_outbox WHERE job_match_id=?",
                Long.class, matchId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE action='JOB_ANALYSIS_REUSED' AND target_id=?::uuid",
                Long.class, matchId
        )).isEqualTo(1);

        // FAILED + force=true: same matchId back to PENDING + one more REQUESTED outbox
        jdbc.update("UPDATE app.job_matches SET status='FAILED' WHERE id=?", matchId);
        HttpResponse<String> forced = browser.postWithHeaders(
                "/api/jobs/" + jobId + "/analyze", "{\"force\":true}", csrf,
                Map.of("Idempotency-Key", "key-force"));
        assertThat(forced.statusCode()).as(forced.body()).isEqualTo(200);
        assertThat(json(forced).at("/data/matchId").asText()).isEqualTo(matchId.toString());
        assertThat(json(forced).at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.job_matches WHERE id=?", String.class, matchId
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_matches WHERE user_id=? AND job_post_id=?",
                Long.class, userId, jobId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.job_match_outbox WHERE job_match_id=? AND event_type='JOB_ANALYSIS_REQUESTED'",
                Long.class, matchId
        )).isEqualTo(2);

        // GET match returns an explicit status
        HttpResponse<String> matchView = browser.get("/api/jobs/" + jobId + "/match");
        assertThat(matchView.statusCode()).as(matchView.body()).isEqualTo(200);
        assertThat(json(matchView).at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(json(matchView).at("/data/score").isNull()).isTrue();

        // Latest-match filtering: an old APPLY/high-score record must never shadow
        // the newer SKIP/low-score record for the same job.
        UUID filterJob = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.job_posts(
                    id, user_id, platform, fingerprint, title, company_name, job_url,
                    source_captured_at, last_seen_at
                ) VALUES (?, ?, 'BOSS', repeat('f', 64), '筛选岗位', '筛选公司',
                          'https://www.zhipin.com/job_detail/f.html', now(), now())
                """, filterJob, userId);
        jdbc.update("""
                INSERT INTO app.job_matches(
                    id, user_id, job_post_id, resume_id, preference_id, status,
                    input_fingerprint, score, decision, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', repeat('c', 64), 90, 'APPLY',
                          now() - interval '1 hour', now())
                """, UUID.randomUUID(), userId, filterJob, resumeId, preferenceId);
        jdbc.update("""
                INSERT INTO app.job_matches(
                    id, user_id, job_post_id, resume_id, preference_id, status,
                    input_fingerprint, score, decision, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', repeat('d', 64), 30, 'SKIP',
                          now(), now())
                """, UUID.randomUUID(), userId, filterJob, resumeId, preferenceId);

        // APPLY must not match (latest is SKIP); SKIP must match
        assertThat(jobIdsInList(browser, "matchDecision=APPLY")).doesNotContain(filterJob);
        assertThat(jobIdsInList(browser, "matchDecision=SKIP")).contains(filterJob);
        // minScore is evaluated against the latest record (30), not the old one (90)
        assertThat(jobIdsInList(browser, "minScore=80")).doesNotContain(filterJob);
        assertThat(jobIdsInList(browser, "minScore=20")).contains(filterJob);
        // matchStatus sees the latest status
        assertThat(jobIdsInList(browser, "matchStatus=SUCCEEDED")).contains(filterJob);
    }

    private java.util.List<UUID> jobIdsInList(BrowserSession browser, String query) throws Exception {
        HttpResponse<String> response = browser.get("/api/jobs?" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (JsonNode item : json(response).at("/data/items")) {
            ids.add(UUID.fromString(item.at("/id").asText()));
        }
        return ids;
    }

    private String preferenceRequest(Integer version) {
        return """
                {
                  "version": %s,
                  "targetTitles": ["Java 开发"],
                  "cities": [],
                  "salaryMinK": 20,
                  "salaryMaxK": 35,
                  "experienceLevels": [],
                  "degreeLevels": [],
                  "industries": [],
                  "companyScales": [],
                  "preferredCompanies": [],
                  "excludedCompanies": [],
                  "excludedKeywords": [],
                  "extraFilters": {}
                }
                """.formatted(version == null ? "null" : version);
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
            return sendWithBody("POST", path, body, csrfToken);
        }

        HttpResponse<String> put(String path, String body, String csrfToken) throws Exception {
            return sendWithBody("PUT", path, body, csrfToken);
        }

        HttpResponse<String> postWithHeaders(String path, String body, String csrfToken,
                                              Map<String, String> extraHeaders) throws Exception {
            return sendWithBodyAndHeaders("POST", path, body, csrfToken, extraHeaders);
        }

        private HttpResponse<String> sendWithBody(String method, String path, String body, String csrfToken) throws Exception {
            return sendWithBodyAndHeaders(method, path, body, csrfToken, Map.of());
        }

        private HttpResponse<String> sendWithBodyAndHeaders(String method, String path, String body,
                                                             String csrfToken, Map<String, String> extraHeaders) throws Exception {
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
