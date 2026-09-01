package com.getjobs.cloud.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.logging.SensitiveDataSanitizer;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile({"api", "worker"})
public class AuditWriter {
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "AUTH_REGISTER",
            "AUTH_LOGIN",
            "AUTH_LOGOUT",
            "AUTH_LOGIN_FAILED",
            "AUTH_ACCOUNT_LOCKED",
            "AUTH_LOGIN_LOCKED",
            "AUTH_LOGIN_DISABLED",
            "AUTH_LOGIN_PENDING",
            "AUTH_EMAIL_VERIFICATION_SENT",
            "AUTH_EMAIL_VERIFIED",
            "AUTH_PASSWORD_RESET_REQUESTED",
            "AUTH_PASSWORD_RESET_COMPLETED",
            "AUTH_ACCOUNT_DELETION_REQUESTED",
            "RESUME_UPLOAD",
            "RESUME_UPLOAD_REJECTED",
            "RESUME_PARSE_SUCCEEDED",
            "RESUME_PARSE_FAILED",
            "RESUME_DELETE_REQUESTED",
            "RESUME_PURGED",
            "PREFERENCE_UPDATED",
            "JOB_ANALYSIS_REQUESTED",
            "JOB_ANALYSIS_SUCCEEDED",
            "JOB_ANALYSIS_FAILED",
            "JOB_ANALYSIS_REUSED",
            "PLUGIN_BIND_CODE_CREATED",
            "PLUGIN_DEVICE_BOUND",
            "PLUGIN_DEVICE_REVOKED",
            "DELIVERY_TASK_CREATED",
            "DELIVERY_TASK_CONFIRMED",
            "DELIVERY_GREETING_UPDATED",
            "DELIVERY_TASK_SKIPPED",
            "PLUGIN_TASK_STARTED",
            "PLUGIN_TASK_SUCCEEDED",
            "PLUGIN_TASK_FAILED",
            "PLUGIN_TASK_PAUSED",
            "PLUGIN_TASKS_PULLED",
            "PLUGIN_TASKS_BATCH_PAUSED",
            "PLUGIN_JOB_CAPTURED",
            "ADMIN_QUOTA_ADJUSTED"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(
            UUID userId,
            String actorType,
            UUID actorId,
            String action,
            String targetType,
            UUID targetId,
            String result,
            String requestId,
            String ipHash,
            String userAgentSummary,
            Map<String, ?> details
    ) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不允许写入未登记的安全审计事件");
        }
        jdbc.queryForObject(
                """
                SELECT app.append_audit_log(
                    CAST(? AS uuid), CAST(? AS varchar), CAST(? AS uuid), CAST(? AS varchar),
                    CAST(? AS varchar), CAST(? AS uuid), CAST(? AS varchar), CAST(? AS varchar),
                    CAST(? AS char(64)), CAST(? AS varchar), CAST(? AS jsonb)
                )
                """,
                Long.class,
                userId,
                actorType,
                actorId,
                action,
                targetType,
                targetId,
                result,
                requestId,
                ipHash,
                userAgentSummary,
                toJson(details)
        );
    }

    private String toJson(Map<String, ?> details) {
        try {
            String serialized = objectMapper.writeValueAsString(details == null ? Map.of() : details);
            // 纵深防御：即使调用方误传了凭证/邮箱/手机号等字段，也绝不落入 details。
            return SensitiveDataSanitizer.sanitize(serialized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化安全审计详情", exception);
        }
    }
}
