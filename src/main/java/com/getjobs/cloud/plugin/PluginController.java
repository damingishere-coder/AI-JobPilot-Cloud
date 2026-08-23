package com.getjobs.cloud.plugin;

import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web-session endpoints for plugin binding: bind-code generation and device
 * management. These run under the Web session chain with CSRF protection.
 */
@RestController
@RequestMapping("/api/plugin")
@Profile("api")
public class PluginController {
    private final CurrentUser currentUser;
    private final PluginService plugins;
    private final AuditLogService auditLogs;

    public PluginController(CurrentUser currentUser, PluginService plugins, AuditLogService auditLogs) {
        this.currentUser = currentUser;
        this.plugins = plugins;
        this.auditLogs = auditLogs;
    }

    /** Issue a one-time bind code shown to the user for plugin pairing. */
    @PostMapping("/bind-code")
    public org.springframework.http.ResponseEntity<ApiResponse<PluginModels.BindCodeResult>> createBindCode(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        PluginModels.BindCodeResult result = plugins.createBindCode(principal.userId(), idempotencyKey);
        auditLogs.append(
                principal.userId(), principal.role(), "PLUGIN_BIND_CODE_CREATED",
                "PLUGIN_BIND_CODE", null, "SUCCESS",
                RequestMetadata.from(servletRequest),
                Map.of("expiresInSeconds", result.expiresInSeconds())
        );
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @GetMapping("/devices")
    public ApiResponse<List<PluginModels.DeviceView>> listDevices() {
        return ApiResponse.success(plugins.listDevices(currentUser.require().userId()));
    }

    @PostMapping("/devices/{id}/revoke")
    public ApiResponse<PluginModels.RevokeDeviceResult> revokeDevice(
            @PathVariable UUID id,
            @RequestBody(required = false) PluginModels.RevokeDeviceRequest request,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        String reason = request == null ? null : request.reason();
        PluginModels.RevokeDeviceResult result = plugins.revokeDevice(principal.userId(), id, reason);
        if (result == null) {
            // Unified 404: missing devices and other users' devices look identical.
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "设备不存在");
        }
        auditLogs.append(
                principal.userId(), principal.role(), "PLUGIN_DEVICE_REVOKED",
                "PLUGIN_DEVICE", id, "SUCCESS",
                RequestMetadata.from(servletRequest),
                Map.of()
        );
        return ApiResponse.success(result);
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Idempotency-Key 不能为空且不能超过 128 个字符"
            );
        }
    }
}
