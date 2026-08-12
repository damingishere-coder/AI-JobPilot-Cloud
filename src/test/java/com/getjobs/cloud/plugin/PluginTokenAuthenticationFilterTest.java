package com.getjobs.cloud.plugin;

import com.getjobs.cloud.auth.SecurityResponseWriter;
import com.getjobs.cloud.plugin.PluginRepository.TokenAuthRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the stateless plugin-token filter: a successful
 * authentication must leave the SecurityContext credentials slot null and the
 * principal free of any token/hash material, and errors must never echo the
 * token value.
 */
class PluginTokenAuthenticationFilterTest {
    private static final String TOKEN = "ajp_plg_sentinel-token-value-1234567890";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulAuthenticationKeepsCredentialsNullAndPrincipalClean() throws Exception {
        PluginRepository repository = mock(PluginRepository.class);
        TokenAuthRecord record = new TokenAuthRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ACTIVE",
                List.of("tasks:read", "tasks:write"), Instant.now().plusSeconds(3600),
                "ACTIVE", "测试用户", "ACTIVE", List.of("BOSS"), "1.2.0", "测试设备"
        );
        when(repository.authenticate(any(), any())).thenReturn(Optional.of(record));

        PluginTokenAuthenticationFilter filter = new PluginTokenAuthenticationFilter(
                repository, new PluginProperties(), new SecurityResponseWriter(new ObjectMapper())
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/plugin/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getPrincipal()).isInstanceOf(PluginPrincipal.class);
            assertThat(authentication.toString()).doesNotContain(TOKEN);
            PluginPrincipal principal = (PluginPrincipal) authentication.getPrincipal();
            assertThat(principal.toString()).doesNotContain(TOKEN);
            assertThat(principal.userId()).isEqualTo(record.userId());
        };

        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    void failedAuthenticationNeverEchoesTheToken() throws Exception {
        PluginRepository repository = mock(PluginRepository.class);
        when(repository.authenticate(any(), any())).thenReturn(Optional.empty());

        PluginTokenAuthenticationFilter filter = new PluginTokenAuthenticationFilter(
                repository, new PluginProperties(), new SecurityResponseWriter(new ObjectMapper())
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/plugin/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run for an unknown token");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain(TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonNumericExtensionVersionsAreRejectedAndInvalidSegmentsAreNotSilentlyZeroed() {
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.2.0", "1.1.0")).isTrue();
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.0.0", "1.1.0")).isFalse();
        // Malformed versions are rejected outright, not parsed as zeros.
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.2.x", "1.1.0")).isFalse();
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.2.0-beta", "1.1.0")).isFalse();
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.2.0", "1.1.x")).isTrue();
        assertThat(PluginTokenAuthenticationFilter.versionSupported(null, "1.1.0")).isFalse();
        assertThat(PluginTokenAuthenticationFilter.versionSupported("2.0", "1.9.9")).isTrue();
        assertThat(PluginTokenAuthenticationFilter.versionSupported("1.9.9", "2.0")).isFalse();
    }

    @Test
    void invalidConfiguredMinimumVersionFailsAtConfigurationTimeInsteadOfDisablingTheCheck() {
        PluginProperties properties = new PluginProperties();
        properties.setMinExtensionVersion("1.2.0");
        assertThat(properties.getMinExtensionVersion()).isEqualTo("1.2.0");
        // A non-empty minimum must be a strict numeric version; anything else
        // is a configuration error, never a silent "no minimum version".
        assertThatThrownBy(() -> properties.setMinExtensionVersion("1.2.x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMinExtensionVersion("latest"))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setMinExtensionVersion("");
        assertThat(properties.getMinExtensionVersion()).isEmpty();
    }
}
