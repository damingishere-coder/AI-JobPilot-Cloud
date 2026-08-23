package com.getjobs.application.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    private static final String DEV_EXTENSION_ORIGIN = "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc";

    private final CorsFilter corsFilter = new CorsConfig().corsFilter(new AppCorsProperties());

    @Test
    void allowsExactDevelopmentExtensionOriginForBossCollectionEndpoint() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/boss/chrome/jobs",
                DEV_EXTENSION_ORIGIN
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(DEV_EXTENSION_ORIGIN);
    }

    @Test
    void allowsExactDevelopmentExtensionOriginForBossDeliveryResultEndpoint() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/boss/jobs/123/delivery-result",
                DEV_EXTENSION_ORIGIN
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo(DEV_EXTENSION_ORIGIN);
    }

    @Test
    void rejectsUnknownExtensionOriginInsteadOfAnyWildcard() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/boss/chrome/jobs",
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop"
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getContentAsString()).contains("Invalid CORS request");
    }

    @Test
    void rejectsWildcardExtensionOrigin() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/boss/chrome/jobs",
                "chrome-extension://*"
        );

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsChromeExtensionForUnrelatedApi() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/config",
                DEV_EXTENSION_ORIGIN
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Invalid CORS request");
    }

    @Test
    void rejectsOrdinaryWebsiteForBossCollectionEndpoint() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/boss/chrome/jobs",
                "https://example.com"
        );

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void keepsLocalFrontendAccessForAllApis() throws Exception {
        MockHttpServletResponse response = preflight(
                "/api/config",
                "http://localhost:6866"
        );

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:6866");
    }

    @Test
    void rejectsUnlistedCorsRequestHeadersAndMethods() throws Exception {
        MockHttpServletResponse unknownHeader = preflight(
                corsFilter, "/api/config", "http://localhost:6866", "POST", "x-internal-secret"
        );
        assertThat(unknownHeader.getStatus()).isEqualTo(403);

        MockHttpServletResponse unknownMethod = preflight(
                corsFilter, "/api/config", "http://localhost:6866", "TRACE", "content-type"
        );
        assertThat(unknownMethod.getStatus()).isEqualTo(403);
    }

    @Test
    void configuredWebOriginsReplaceTheLocalDefaults() throws Exception {
        AppCorsProperties properties = new AppCorsProperties();
        properties.setAllowedOrigins(List.of("https://app.example.com"));
        CorsFilter configured = new CorsConfig().corsFilter(properties);

        MockHttpServletResponse allowed = preflight(
                configured, "/api/config", "https://app.example.com");
        assertThat(allowed.getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = preflight(
                configured, "/api/config", "http://localhost:6866");
        assertThat(blocked.getStatus()).isEqualTo(403);
    }

    @Test
    void propertyValidationRejectsWildcardsPatternsAndEmptyWebOrigins() {
        AppCorsProperties properties = new AppCorsProperties();
        assertThatThrownBy(() -> properties.setAllowedOrigins(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedOrigins(List.of("http://*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedOrigins(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(
                List.of("chrome-extension://*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(
                List.of("chrome-extension://[a-p]{32}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedExtensionOrigins(
                List.of("https://example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredExtensionOriginsReplaceTheDefaultExactly() throws Exception {
        AppCorsProperties properties = new AppCorsProperties();
        String productionOrigin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
        properties.setAllowedExtensionOrigins(List.of(productionOrigin));
        CorsFilter configured = new CorsConfig().corsFilter(properties);

        MockHttpServletResponse allowed = preflight(
                configured, "/api/boss/chrome/jobs", productionOrigin);
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo(productionOrigin);

        MockHttpServletResponse devBlocked = preflight(
                configured, "/api/boss/chrome/jobs", DEV_EXTENSION_ORIGIN);
        assertThat(devBlocked.getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse preflight(String path, String origin) throws Exception {
        return preflight(corsFilter, path, origin);
    }

    private MockHttpServletResponse preflight(CorsFilter filter, String path, String origin) throws Exception {
        return preflight(filter, path, origin, "POST", "content-type");
    }

    private MockHttpServletResponse preflight(
            CorsFilter filter,
            String path,
            String origin,
            String method,
            String headers
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", headers);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
