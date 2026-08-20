package com.getjobs.cloud.plugin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the Cloud plugin CORS contract: only the exact extension
 * origin derived from the committed manifest public key is allowed, only the
 * plugin paths are registered, methods/headers are fixed and credentials are
 * disabled. Actual GET/POST requests still pass through the plugin token
 * filter (covered by {@link PluginDeliveryIntegrationTest}); CORS never
 * replaces authentication.
 */
class PluginCorsConfigurationTest {

    private static final String DEV_EXTENSION_ID = "ompipmnadogogfbebnmjgbbcadildpbc";
    private static final String DEV_ORIGIN = "chrome-extension://" + DEV_EXTENSION_ID;

    private CorsConfigurationSource newSource(PluginProperties properties) {
        return new PluginSecurityConfiguration().pluginCorsConfigurationSource(properties);
    }

    private static CorsConfiguration configFor(
            CorsConfigurationSource source, String method, String path
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Origin", DEV_ORIGIN);
        return source.getCorsConfiguration(request);
    }

    // ---- 1. Exact origin whitelist ----

    @Test
    void defaultPropertiesAllowExactlyTheDerivedDevelopmentExtensionId() {
        CorsConfiguration configuration = configFor(newSource(new PluginProperties()), "GET", "/api/plugin/me");

        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin(DEV_ORIGIN)).isEqualTo(DEV_ORIGIN);
        // 其他扩展 ID、普通网站与通配 origin 一律拒绝。
        assertThat(configuration.checkOrigin("chrome-extension://abcdefghijklmnopabcdefghijklmnop")).isNull();
        assertThat(configuration.checkOrigin("https://app.example.com")).isNull();
        assertThat(configuration.checkOrigin("chrome-extension://*")).isNull();
        assertThat(configuration.checkOrigin("null")).isNull();
    }

    @Test
    void configuredOriginsReplaceTheDefaultAndRejectWildcards() {
        PluginProperties properties = new PluginProperties();
        properties.setAllowedExtensionOrigins(List.of(
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop"
        ));
        CorsConfiguration configuration = configFor(newSource(properties), "GET", "/api/plugin/me");

        assertThat(configuration.checkOrigin("chrome-extension://abcdefghijklmnopabcdefghijklmnop"))
                .isEqualTo("chrome-extension://abcdefghijklmnopabcdefghijklmnop");
        assertThat(configuration.checkOrigin(DEV_ORIGIN)).isNull();
        assertThat(configuration.getAllowedOrigins()).doesNotContain("chrome-extension://*");
    }

    @Test
    void propertyValidationRejectsWildcardsPatternsAndNonExtensionOrigins() {
        PluginProperties properties = new PluginProperties();
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(List.of("chrome-extension://*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(List.of("chrome-extension://[a-p]{32}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(List.of("https://app.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(List.of("chrome-extension://ompipmnadogogogfbebnmjgbbcadildpbc")))
                .isInstanceOf(IllegalArgumentException.class);
        // 空白项过滤；空列表表示不允许任何扩展 origin，而不是回退默认值。
        properties.setAllowedExtensionOrigins(List.of(" ", ""));
        assertThat(properties.getAllowedExtensionOrigins()).isEmpty();
    }

    // ---- 2. Path scope ----

    @Test
    void onlyPluginPathsAreRegisteredAndWebPathsStayOutside() {
        CorsConfigurationSource source = newSource(new PluginProperties());
        assertThat(configFor(source, "POST", "/api/plugin/bind")).isNotNull();
        assertThat(configFor(source, "GET", "/api/plugin/me")).isNotNull();
        assertThat(configFor(source, "GET", "/api/plugin/tasks/pending")).isNotNull();
        assertThat(configFor(source, "POST", "/api/plugin/tasks/00000000-0000-0000-0000-000000000000/start")).isNotNull();
        assertThat(configFor(source, "GET", "/api/jobs")).isNull();
        assertThat(configFor(source, "POST", "/api/delivery/tasks")).isNull();
        assertThat(configFor(source, "GET", "/api/auth/login")).isNull();
    }

    // ---- 3. Methods, headers, credentials ----

    @Test
    void methodsHeadersAndCredentialsAreRestrictedToThePluginContract() {
        CorsConfiguration configuration = configFor(newSource(new PluginProperties()), "GET", "/api/plugin/me");

        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.GET)).isNotNull();
        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.POST)).isNotNull();
        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.OPTIONS)).isNotNull();
        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.PUT)).isNull();
        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.DELETE)).isNull();
        assertThat(configuration.checkHttpMethod(org.springframework.http.HttpMethod.PATCH)).isNull();

        assertThat(configuration.checkHeaders(List.of("authorization"))).hasSize(1);
        assertThat(configuration.checkHeaders(List.of("content-type", "idempotency-key"))).hasSize(2);
        assertThat(configuration.checkHeaders(List.of("authorization", "content-type", "idempotency-key"))).hasSize(3);
        assertThat(configuration.checkHeaders(List.of("x-custom-header"))).isNull();
        // Spring 6.2 只回显匹配子集：cookie 永远不会出现在允许头里。
        assertThat(configuration.checkHeaders(List.of("authorization", "cookie")))
                .containsExactly("authorization");

        assertThat(configuration.getAllowCredentials()).isFalse();
    }

    // ---- 4. Preflight behavior via DefaultCorsProcessor ----

    @Test
    void validPreflightIsAnsweredWithoutReachingTheTokenFilter() throws IOException {
        CorsConfigurationSource source = newSource(new PluginProperties());
        DefaultCorsProcessor processor = new DefaultCorsProcessor();

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/plugin/tasks/00000000-0000-0000-0000-000000000000/start");
        request.addHeader("Origin", DEV_ORIGIN);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization, content-type, idempotency-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = processor.processRequest(source.getCorsConfiguration(request), request, response);

        assertThat(handled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(DEV_ORIGIN);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET").contains("POST").contains("OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).contains("authorization").contains("content-type").contains("idempotency-key");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void preflightFromWrongOriginsIsRejected() throws IOException {
        CorsConfigurationSource source = newSource(new PluginProperties());
        DefaultCorsProcessor processor = new DefaultCorsProcessor();
        List<String> rejected = List.of(
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
                "chrome-extension://*",
                "https://app.example.com",
                "null"
        );
        for (String origin : rejected) {
            MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/plugin/me");
            request.addHeader("Origin", origin);
            request.addHeader("Access-Control-Request-Method", "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean handled = processor.processRequest(source.getCorsConfiguration(request), request, response);

            assertThat(handled).as("origin %s must be rejected", origin).isFalse();
            assertThat(response.getHeader("Access-Control-Allow-Origin")).as("origin %s", origin).isNull();
        }
    }

    // ---- 5. Manifest key / default origin consistency ----

    @Test
    void defaultOriginEqualsTheIdDerivedFromTheCommittedManifestPublicKey() throws Exception {
        Path manifest = Path.of("chrome-extension", "manifest.json");
        assertThat(Files.exists(manifest)).isTrue();
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        String key = json.replaceAll("(?s).*\"key\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(key).matches("^[A-Za-z0-9+/=]+$");

        byte[] der = Base64.getDecoder().decode(key);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder id = new StringBuilder(32);
        for (int index = 0; index < 16; index++) {
            int byteValue = digest[index] & 0xff;
            id.append((char) ('a' + (byteValue >> 4)));
            id.append((char) ('a' + (byteValue & 0x0f)));
        }

        assertThat(id.toString()).isEqualTo(DEV_EXTENSION_ID);
        assertThat(new PluginProperties().getAllowedExtensionOrigins())
                .containsExactly("chrome-extension://" + DEV_EXTENSION_ID);
    }
}
