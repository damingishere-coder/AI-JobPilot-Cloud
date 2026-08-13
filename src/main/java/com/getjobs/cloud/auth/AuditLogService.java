package com.getjobs.cloud.auth;

import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("api")
public class AuditLogService {
    private final AuditWriter writer;
    private final SecurityFingerprintService fingerprints;

    public AuditLogService(
            AuditWriter writer,
            SecurityFingerprintService fingerprints
    ) {
        this.writer = writer;
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
        append(userId, actorRole, action, "USER", userId, result, request, details);
    }

    public void append(
            UUID userId,
            UserRole actorRole,
            String action,
            String targetType,
            UUID targetId,
            String result,
            RequestMetadata request,
            Map<String, ?> details
    ) {
        String actorType = actorRole == null ? "SYSTEM" : actorRole.name();
        UUID actorId = actorRole == null ? null : userId;
        writer.append(
                userId,
                actorType,
                actorId,
                action,
                targetType,
                targetId,
                result,
                MDC.get(RequestIdFilter.MDC_KEY),
                fingerprints.hash(request.remoteAddress()),
                summarizeUserAgent(request.userAgent()),
                details
        );
    }

    public static String summarizeUserAgent(String userAgent) {
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
