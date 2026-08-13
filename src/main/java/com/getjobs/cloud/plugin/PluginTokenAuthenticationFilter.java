package com.getjobs.cloud.plugin;

import com.getjobs.cloud.auth.SecurityResponseWriter;
import com.getjobs.cloud.plugin.PluginRepository.TokenAuthRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Stateless Bearer-token authentication for the plugin API. The token value
 * is hashed and resolved through the SECURITY DEFINER lookup (prefix locates
 * the candidates, the hash comparison is constant-time inside PostgreSQL);
 * the plaintext value is never logged, persisted or echoed, and never enters
 * the SecurityContext credentials. Errors map to
 * PLUGIN_TOKEN_INVALID / PLUGIN_TOKEN_EXPIRED / DEVICE_REVOKED /
 * ACCOUNT_DISABLED without disclosing whether a token exists.
 */
@Component
@Profile("api")
public class PluginTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int TOKEN_PREFIX_LENGTH = 16;

    private final PluginRepository repository;
    private final PluginProperties properties;
    private final SecurityResponseWriter responses;

    public PluginTokenAuthenticationFilter(
            PluginRepository repository,
            PluginProperties properties,
            SecurityResponseWriter responses
    ) {
        this.repository = repository;
        this.properties = properties;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // The anonymous bind call authenticates with a one-time code instead.
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/plugin/bind".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            responses.write(response, 401, "PLUGIN_TOKEN_INVALID", "缺少有效的插件凭证", false);
            return;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank() || token.length() > 256 || token.length() < TOKEN_PREFIX_LENGTH) {
            responses.write(response, 401, "PLUGIN_TOKEN_INVALID", "插件凭证格式不正确", false);
            return;
        }

        Optional<TokenAuthRecord> record;
        try {
            record = repository.authenticate(tokenPrefix(token), sha256(token));
        } catch (DataAccessException exception) {
            responses.write(response, 503, "DEPENDENCY_UNAVAILABLE", "服务暂不可用，请稍后再试", true);
            return;
        }

        if (record.isEmpty()) {
            responses.write(response, 401, "PLUGIN_TOKEN_INVALID", "插件凭证无效，请重新绑定", false);
            return;
        }

        TokenAuthRecord auth = record.get();
        // Device state wins over token state so revocation reports DEVICE_REVOKED;
        // tokens rotated away on re-bind still report PLUGIN_TOKEN_INVALID.
        if (!"ACTIVE".equals(auth.deviceStatus())) {
            responses.write(response, 403, "DEVICE_REVOKED", "设备已被撤销，请重新绑定", false);
            return;
        }
        if (!"ACTIVE".equals(auth.userStatus())) {
            responses.write(response, 403, "ACCOUNT_DISABLED", "账号当前不可用", false);
            return;
        }
        if ("EXPIRED".equals(auth.tokenStatus())) {
            responses.write(response, 401, "PLUGIN_TOKEN_EXPIRED", "插件凭证已过期，请重新绑定", false);
            return;
        }
        if (!"ACTIVE".equals(auth.tokenStatus())) {
            responses.write(response, 401, "PLUGIN_TOKEN_INVALID", "插件凭证无效，请重新绑定", false);
            return;
        }
        if (!versionSupported(auth.deviceExtensionVersion(), properties.getMinExtensionVersion())) {
            responses.write(response, 426, "EXTENSION_UPGRADE_REQUIRED", "插件版本过低，请升级后重试", false);
            return;
        }

        // The resolved principal carries only the minimal trusted fields; the
        // credentials slot stays null so the plaintext token never enters the
        // SecurityContext, its toString, logs or audit output.
        PluginPrincipal principal = new PluginPrincipal(
                auth.tokenId(), auth.userId(), auth.deviceId(), auth.deviceName(),
                auth.userDisplayName(), auth.scopes(), auth.capabilities(),
                auth.deviceExtensionVersion(), auth.tokenExpiresAt()
        );
        List<GrantedAuthority> authorities = auth.scopes().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)
        );

        // Throttled liveness maintenance; a failure here must not fail the request.
        try {
            repository.touch(auth.tokenId(), auth.deviceId(),
                    properties.getLastSeenUpdateIntervalSeconds());
        } catch (DataAccessException ignored) {
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Strict numeric version comparison. Versions must consist of 1-4 numeric
     * segments; anything else is unsupported instead of being silently parsed
     * with missing segments treated as zero.
     */
    static boolean versionSupported(String current, String minimum) {
        if (minimum == null || minimum.isBlank() || !isNumericVersion(minimum)) {
            return true;
        }
        if (current == null || !isNumericVersion(current)) {
            return false;
        }
        String[] currentParts = current.split("\\.", -1);
        String[] minimumParts = minimum.split("\\.", -1);
        int length = Math.max(currentParts.length, minimumParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int minimumPart = i < minimumParts.length ? Integer.parseInt(minimumParts[i]) : 0;
            if (currentPart > minimumPart) {
                return true;
            }
            if (currentPart < minimumPart) {
                return false;
            }
        }
        return true;
    }

    static boolean isNumericVersion(String version) {
        return version != null && version.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}");
    }

    private static String tokenPrefix(String token) {
        return token.substring(0, TOKEN_PREFIX_LENGTH);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
