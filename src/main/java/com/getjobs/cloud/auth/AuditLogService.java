package com.getjobs.cloud.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("api")
public class AuditLogService {
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "AUTH_REGISTER",
            "AUTH_LOGIN",
            "AUTH_LOGOUT",
            "AUTH_LOGIN_FAILED",
            "AUTH_ACCOUNT_LOCKED",
            "AUTH_LOGIN_LOCKED",
            "AUTH_LOGIN_DISABLED",
            "AUTH_LOGIN_PENDING"
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SecurityFingerprintService fingerprints;

    public AuditLogService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SecurityFingerprintService fingerprints
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fingerprints = fingerprints;
    }

    public void append(
            UUID userId,
            UserRole actorRole,
            String action,
            String result,
            RequestMetadata request,
            Map<String, ?> details
    ) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不允许写入未登记的安全审计事件");
        }
        String actorType = actorRole == null ? "SYSTEM" : actorRole.name();
        UUID actorId = actorRole == null ? null : userId;
        jdbc.queryForObject(
                """
                SELECT app.append_audit_log(
                    CAST(? AS uuid), CAST(? AS varchar), CAST(? AS uuid), CAST(? AS varchar),
                    CAST('USER' AS varchar), CAST(? AS uuid), CAST(? AS varchar), CAST(? AS varchar),
                    CAST(? AS char(64)), CAST(? AS varchar), CAST(? AS jsonb)
                )
                """,
                Long.class,
                userId,
                actorType,
                actorId,
                action,
                userId,
                result,
                MDC.get(RequestIdFilter.MDC_KEY),
                fingerprints.hash(request.remoteAddress()),
                summarizeUserAgent(request.userAgent()),
                toJson(details)
        );
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化安全审计详情", exception);
        }
    }

    static String summarizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        String browser = lower.contains("edg/") ? "Edge"
                : lower.contains("chrome/") ? "Chrome"
                : lower.contains("firefox/") ? "Firefox"
                : lower.contains("safari/") ? "Safari"
                : "Other";
        String system = lower.contains("windows") ? "Windows"
                : lower.contains("mac os") ? "macOS"
                : lower.contains("android") ? "Android"
                : lower.contains("iphone") || lower.contains("ipad") ? "iOS"
                : lower.contains("linux") ? "Linux"
                : "Other";
        return browser + "/" + system;
    }
}
