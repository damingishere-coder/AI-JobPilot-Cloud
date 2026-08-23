package com.getjobs.cloud.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 后台管理（/api/admin）领域与 API 模型。
 *
 * <p>所有视图只暴露数据库函数已脱敏/聚合后的窄字段：邮箱始终为 emailMasked，
 * 绝不包含 password_hash、token/API Key/Cookie、简历、Prompt 或模型原始响应。</p>
 */
public final class AdminModels {

    private AdminModels() {
    }

    /** 单资源额度视图（total/used/reserved/remaining）。 */
    public record ResourceQuotaView(
            String resourceCode,
            long total,
            long used,
            long reserved,
            long remaining
    ) {
    }

    /** 后台用户视图（列表行与详情共用；列表行携带总条数）。 */
    public record UserAdminView(
            UUID id,
            String emailMasked,
            String role,
            String status,
            Instant createdAt,
            String plan,
            ResourceQuotaView analysisQuota,
            ResourceQuotaView deliveryQuota,
            long jobCount,
            long aiAnalysisCount,
            long deliveryTaskCount,
            long successCount,
            long failedCount,
            long activeDeviceCount,
            long totalCount
    ) {
    }

    /** 用户分页列表。 */
    public record UserPage(long total, List<UserAdminView> users) {
    }

    /** 单用户当前周期额度行。 */
    public record UserQuotaRowView(
            UUID quotaId,
            String plan,
            String resourceCode,
            long total,
            long used,
            long reserved,
            long remaining,
            Instant resetAt
    ) {
    }

    /** 仪表盘聚合。 */
    public record DashboardView(
            long totalUsers,
            long activeUsers,
            long jobs,
            long aiAnalyses,
            long deliveryTasks,
            long successCount,
            long failedCount,
            long activeDevices,
            long recentFailures
    ) {
    }

    /** 最近审计事件（不含 details/ip_hash/user_agent_summary/request_id）。 */
    public record AuditLogView(
            long id,
            UUID userId,
            String userEmailMasked,
            String actorType,
            String action,
            String targetType,
            UUID targetId,
            String result,
            Instant createdAt
    ) {
    }

    /** 最近失败投递任务。 */
    public record DeliveryFailureView(
            UUID taskId,
            UUID userId,
            String emailMasked,
            String platform,
            String status,
            String lastErrorCode,
            String errorMessage,
            Instant updatedAt
    ) {
    }

    /** PUT /api/admin/users/{id}/quota 请求体。 */
    public record QuotaAdjustRequest(
            @NotBlank
            @Pattern(regexp = "FREE|MONTHLY|PREMIUM_MONTHLY|JOB_SEASON|COACHING",
                    message = "plan 必须是 FREE/MONTHLY/PREMIUM_MONTHLY/JOB_SEASON/COACHING")
            String plan,
            @NotNull @Min(0) @Max(1_000_000) Long analysisQuotaTotal,
            @NotNull @Min(0) @Max(1_000_000) Long deliveryQuotaTotal,
            @NotBlank @Size(max = 200) String reason
    ) {
    }

    /** 调额成功后的当前状态。 */
    public record QuotaAdjustResult(
            String plan,
            ResourceQuotaView analysisQuota,
            ResourceQuotaView deliveryQuota
    ) {
    }

    /** 数据库调额函数的返回行。applied=false 表示本次调用未产生新的额度流水（replay 或数值未变）。 */
    public record QuotaAdjustOutcome(
            String outcome,
            boolean applied,
            String planCode,
            long analysisOldTotal,
            long analysisTotal,
            long analysisUsed,
            long analysisReserved,
            long analysisRemaining,
            long deliveryOldTotal,
            long deliveryTotal,
            long deliveryUsed,
            long deliveryReserved,
            long deliveryRemaining
    ) {
        boolean ok() {
            return "OK".equals(outcome);
        }
    }
}
