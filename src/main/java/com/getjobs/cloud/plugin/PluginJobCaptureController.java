package com.getjobs.cloud.plugin;

import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.ratelimit.RateLimitProperties;
import com.getjobs.cloud.web.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plugin job capture upload endpoints. Only reachable with a plugin token
 * carrying SCOPE_jobs:write; the user and device ids come exclusively from the
 * authenticated principal. The endpoint is rate limited per deviceId (never by
 * the raw token) and writes one PLUGIN_JOB_CAPTURED audit row per request.
 */
@RestController
@RequestMapping("/api/plugin/jobs")
@Profile("api")
public class PluginJobCaptureController {
    private static final String CAPTURE_RATE_KEY_PREFIX = "ai-jobpilot:api:rate:plugin-job-capture:device:";

    private final CurrentPlugin currentPlugin;
    private final PluginCaptureService capture;
    private final ApiRateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public PluginJobCaptureController(
            CurrentPlugin currentPlugin,
            PluginCaptureService capture,
            ApiRateLimiter rateLimiter,
            RateLimitProperties rateLimits
    ) {
        this.currentPlugin = currentPlugin;
        this.capture = capture;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/capture")
    public ApiResponse<PluginModels.CaptureResult> capture(
            @RequestBody PluginModels.CaptureJobRequest request
    ) {
        PluginPrincipal principal = currentPlugin.require();
        checkRateLimit(principal);
        return ApiResponse.success(capture.capture(principal, request));
    }

    @PostMapping("/batch-capture")
    public ApiResponse<PluginModels.CaptureBatchResult> captureBatch(
            @RequestBody PluginModels.CaptureBatchRequest request
    ) {
        PluginPrincipal principal = currentPlugin.require();
        checkRateLimit(principal);
        return ApiResponse.success(capture.captureBatch(principal, request));
    }

    private void checkRateLimit(PluginPrincipal principal) {
        // 限流按认证后的 deviceId 维度（服务端从 Token 哈希解析），
        // 原始 Token 绝不进入 Redis key。
        rateLimiter.check(
                CAPTURE_RATE_KEY_PREFIX + principal.deviceId(),
                rateLimits.getPluginJobCaptureLimit(),
                rateLimits.getPluginJobCaptureWindow()
        );
    }
}
