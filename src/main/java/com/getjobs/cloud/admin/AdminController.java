package com.getjobs.cloud.admin;

import com.getjobs.cloud.admin.AdminModels.AuditLogView;
import com.getjobs.cloud.admin.AdminModels.DashboardView;
import com.getjobs.cloud.admin.AdminModels.DeliveryFailureView;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustRequest;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustResult;
import com.getjobs.cloud.admin.AdminModels.UserAdminView;
import com.getjobs.cloud.admin.AdminModels.UserPage;
import com.getjobs.cloud.admin.AdminModels.UserQuotaRowView;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.web.ApiError;
import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 后台管理 API（仅 ACTIVE ADMIN）。身份只来自会话（CurrentUser），不允许请求
 * 参数指定 actor；数据库函数内部还会再次校验管理员身份。
 */
@RestController
@RequestMapping("/api/admin")
@Profile("api")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService admins;

    public AdminController(AdminService admins) {
        this.admins = admins;
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<UserPage>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return noStore(ApiResponse.success(admins.listUsers(page, size)));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserAdminView>> getUser(@PathVariable UUID id) {
        return noStore(ApiResponse.success(admins.userDetail(id)));
    }

    @GetMapping("/users/{id}/quota")
    public ResponseEntity<ApiResponse<List<UserQuotaRowView>>> getUserQuota(@PathVariable UUID id) {
        return noStore(ApiResponse.success(admins.userQuota(id)));
    }

    @PutMapping("/users/{id}/quota")
    public ResponseEntity<ApiResponse<QuotaAdjustResult>> setQuota(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody QuotaAdjustRequest request,
            HttpServletRequest servletRequest
    ) {
        return noStore(ApiResponse.success(admins.adjustQuota(
                id,
                idempotencyKey,
                request,
                RequestMetadata.from(servletRequest)
        )));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardView>> dashboard() {
        return noStore(ApiResponse.success(admins.dashboard()));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<List<AuditLogView>>> auditLogs(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return noStore(ApiResponse.success(admins.auditLogs(limit)));
    }

    @GetMapping("/delivery-failures")
    public ResponseEntity<ApiResponse<List<DeliveryFailureView>>> deliveryFailures(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return noStore(ApiResponse.success(admins.deliveryFailures(limit)));
    }

    /**
     * 方法级鉴权（@PreAuthorize）拒绝时返回 403，避免被全局兜底处理器当作 500。
     * 只处理鉴权拒绝；CSRF 拒绝仍由安全过滤链的 accessDeniedHandler 处理。
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(AuthorizationDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure(new ApiError(
                        "FORBIDDEN", "没有权限执行该操作", List.of(), false
                )));
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
