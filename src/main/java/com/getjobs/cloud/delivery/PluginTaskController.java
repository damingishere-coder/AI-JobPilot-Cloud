package com.getjobs.cloud.delivery;

import com.getjobs.cloud.plugin.CurrentPlugin;
import com.getjobs.cloud.plugin.PluginPrincipal;
import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.ratelimit.RateLimitProperties;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Plugin task execution endpoints. Only reachable with a plugin token; the
 * user and device ids come exclusively from the authenticated principal. Every
 * state-changing call requires a valid Idempotency-Key. The state transition
 * and its security audit are committed in one transaction by
 * {@link DeliveryService}; replays never duplicate the audit.
 */
@RestController
@RequestMapping("/api/plugin/tasks")
@Profile("api")
public class PluginTaskController {
    private static final String PENDING_RATE_KEY_PREFIX = "ai-jobpilot:api:rate:plugin-task-poll:device:";

    private final CurrentPlugin currentPlugin;
    private final DeliveryService delivery;
    private final ApiRateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public PluginTaskController(
            CurrentPlugin currentPlugin,
            DeliveryService delivery,
            ApiRateLimiter rateLimiter,
            RateLimitProperties rateLimits
    ) {
        this.currentPlugin = currentPlugin;
        this.delivery = delivery;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/pending")
    public ApiResponse<DeliveryModels.PendingTasksResult> pending(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String platform
    ) {
        PluginPrincipal principal = currentPlugin.require();
        // 轮询限流按认证后的 deviceId 维度（服务端从 Token 哈希解析），
        // 原始 Token 绝不进入 Redis key。
        rateLimiter.check(
                PENDING_RATE_KEY_PREFIX + principal.deviceId(),
                rateLimits.getPluginTaskPollLimit(),
                rateLimits.getPluginTaskPollWindow()
        );
        return ApiResponse.success(delivery.pending(
                principal.userId(), principal.deviceId(), principal.capabilities(), limit, platform
        ));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<DeliveryModels.StartResult> start(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.StartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PluginPrincipal principal = currentPlugin.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        return ApiResponse.success(delivery.start(
                principal.userId(), principal.deviceId(), id, request, idempotencyKey
        ));
    }

    @PostMapping("/{id}/success")
    public ApiResponse<DeliveryModels.SuccessResult> success(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.SuccessRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PluginPrincipal principal = currentPlugin.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        return ApiResponse.success(delivery.success(
                principal.userId(), principal.deviceId(), id, request, idempotencyKey
        ));
    }

    @PostMapping("/{id}/fail")
    public ApiResponse<DeliveryModels.FailResult> fail(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.FailRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PluginPrincipal principal = currentPlugin.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        return ApiResponse.success(delivery.fail(
                principal.userId(), principal.deviceId(), id, request, idempotencyKey
        ));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<DeliveryModels.PauseResult> pause(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.PauseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        PluginPrincipal principal = currentPlugin.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        return ApiResponse.success(delivery.pause(
                principal.userId(), principal.deviceId(), id, request, idempotencyKey
        ));
    }

    /**
     * Batch pause: every RUNNING task of the authenticated user + device moves
     * to PAUSED_NEED_USER. No Idempotency-Key is required because the operation
     * is naturally idempotent (a second call finds no RUNNING tasks). The
     * tasks:write scope is enforced by the security chain.
     */
    @PostMapping("/batch-pause")
    public ApiResponse<DeliveryModels.BatchPauseResult> batchPause(
            @RequestBody(required = false) DeliveryModels.BatchPauseRequest request
    ) {
        PluginPrincipal principal = currentPlugin.require();
        String reason = request == null ? null : request.reason();
        return ApiResponse.success(delivery.batchPause(
                principal.userId(), principal.deviceId(), reason
        ));
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
