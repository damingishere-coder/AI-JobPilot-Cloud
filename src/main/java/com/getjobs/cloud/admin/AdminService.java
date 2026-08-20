package com.getjobs.cloud.admin;

import com.getjobs.cloud.admin.AdminModels.AuditLogView;
import com.getjobs.cloud.admin.AdminModels.DashboardView;
import com.getjobs.cloud.admin.AdminModels.DeliveryFailureView;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustOutcome;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustRequest;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustResult;
import com.getjobs.cloud.admin.AdminModels.ResourceQuotaView;
import com.getjobs.cloud.admin.AdminModels.UserAdminView;
import com.getjobs.cloud.admin.AdminModels.UserPage;
import com.getjobs.cloud.admin.AdminModels.UserQuotaRowView;
import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台管理服务：全部接口都要求 ACTIVE ADMIN 会话（Spring 方法级鉴权 + 数据库
 * 函数内部复核），身份只取自 {@link CurrentUser#require()}，绝不由请求参数指定。
 */
@Service
@Profile("api")
public class AdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final AdminRepository admin;
    private final CurrentUser currentUser;
    private final AuditLogService auditLogs;
    private final TransactionTemplate transactions;

    public AdminService(
            AdminRepository admin,
            CurrentUser currentUser,
            AuditLogService auditLogs,
            PlatformTransactionManager transactionManager
    ) {
        this.admin = admin;
        this.currentUser = currentUser;
        this.auditLogs = auditLogs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public UserPage listUsers(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return admin.listUsers(actor(), safePage, safeSize);
    }

    public UserAdminView userDetail(UUID userId) {
        return admin.findUserDetail(actor(), userId).orElseThrow(() -> notFound());
    }

    public List<UserQuotaRowView> userQuota(UUID userId) {
        return admin.findUserQuotaRows(actor(), userId);
    }

    public DashboardView dashboard() {
        return admin.dashboard(actor());
    }

    public List<AuditLogView> auditLogs(int limit) {
        return admin.listAuditLogs(actor(), clampLimit(limit));
    }

    public List<DeliveryFailureView> deliveryFailures(int limit) {
        return admin.listDeliveryFailures(actor(), clampLimit(limit));
    }

    /**
     * 管理员调额。幂等键只用于派生稳定的 SHA-256 base key（adminId+targetId+key），
     * 明文 key 绝不落库；额度写与管理员审计在同一事务内提交或回滚。
     */
    public QuotaAdjustResult adjustQuota(
            UUID targetUserId,
            String idempotencyKey,
            QuotaAdjustRequest request,
            RequestMetadata requestMetadata
    ) {
        SessionPrincipal actor = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        String baseKey = stableOperationKey(
                actor.userId(),
                targetUserId,
                idempotencyKey,
                request.plan(),
                request.analysisQuotaTotal(),
                request.deliveryQuotaTotal()
        );

        QuotaAdjustOutcome outcome = transactions.execute(status -> {
            QuotaAdjustOutcome applied = admin.adjustQuota(
                    actor.userId(),
                    targetUserId,
                    request.plan(),
                    request.analysisQuotaTotal(),
                    request.deliveryQuotaTotal(),
                    request.reason(),
                    baseKey
            );
            if (!applied.ok()) {
                throw belowUsage();
            }
            // 只在真实产生额度流水时写管理员审计：同一幂等键的 replay 不再重复记账。
            if (applied.applied()) {
                auditLogs.appendAdmin(
                        targetUserId,
                        actor.userId(),
                        "ADMIN_QUOTA_ADJUSTED",
                        "USER",
                        targetUserId,
                        "SUCCESS",
                        requestMetadata,
                        Map.of(
                                "plan", request.plan(),
                                "analysisOldTotal", applied.analysisOldTotal(),
                                "analysisNewTotal", applied.analysisTotal(),
                                "deliveryOldTotal", applied.deliveryOldTotal(),
                                "deliveryNewTotal", applied.deliveryTotal()
                        )
                );
            }
            return applied;
        });

        return new QuotaAdjustResult(
                request.plan(),
                new ResourceQuotaView(
                        "AI_ANALYSIS",
                        outcome.analysisTotal(),
                        outcome.analysisUsed(),
                        outcome.analysisReserved(),
                        outcome.analysisRemaining()
                ),
                new ResourceQuotaView(
                        "DELIVERY_CONFIRM",
                        outcome.deliveryTotal(),
                        outcome.deliveryUsed(),
                        outcome.deliveryReserved(),
                        outcome.deliveryRemaining()
                )
        );
    }

    private UUID actor() {
        return currentUser.require().userId();
    }

    private static long clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key 必须为 1-200 个字符");
        }
    }

    /**
     * 稳定操作指纹：管理员、目标、客户端幂等键和服务端已校验的请求体共同参与 SHA-256。
     * 精确重放映射到同一键；同一客户端 key 若请求体发生变化则成为新的、可审计的调整，
     * 不会静默复用旧流水而造成“额度已改但无日志”。
     */
    static String stableOperationKey(
            UUID adminId,
            UUID targetId,
            String idempotencyKey,
            String plan,
            long analysisTotal,
            long deliveryTotal
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.join("|",
                    adminId.toString(),
                    targetId.toString(),
                    idempotencyKey,
                    plan,
                    Long.toString(analysisTotal),
                    Long.toString(deliveryTotal));
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static ApiException belowUsage() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "QUOTA_BELOW_USAGE",
                "新的额度总量不能低于已用与已预占之和"
        );
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "目标用户不存在");
    }
}
