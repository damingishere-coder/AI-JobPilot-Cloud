package com.getjobs.cloud.plugin;

import com.getjobs.cloud.web.ApiResponse;
import com.getjobs.cloud.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plugin-token endpoints: anonymous bind via one-time code and the device
 * identity view. Both run inside the stateless plugin security chain. The
 * PLUGIN_DEVICE_BOUND audit is written inside the same PostgreSQL transaction
 * as the bind by {@link PluginService}, never after commit.
 */
@RestController
@RequestMapping("/api/plugin")
@Profile("api")
public class PluginBindController {
    private final CurrentPlugin currentPlugin;
    private final PluginService plugins;

    public PluginBindController(CurrentPlugin currentPlugin, PluginService plugins) {
        this.currentPlugin = currentPlugin;
        this.plugins = plugins;
    }

    /**
     * Anonymous one-time-code bind; returns the plaintext token exactly once.
     * The success response is explicitly non-cacheable so intermediaries never
     * retain the single-use plaintext token.
     */
    @PostMapping("/bind")
    public ResponseEntity<ApiResponse<PluginModels.BindResult>> bind(
            @RequestBody PluginModels.BindRequest request,
            HttpServletRequest servletRequest
    ) {
        PluginService.BoundDevice bound = plugins.bindWithOwner(
                request,
                remoteAddress(servletRequest),
                servletRequest.getHeader("User-Agent"),
                MDC.get(RequestIdFilter.MDC_KEY)
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success(bound.result()));
    }

    @GetMapping("/me")
    public ApiResponse<PluginModels.MeResponse> me() {
        return ApiResponse.success(plugins.me(currentPlugin.require()));
    }

    /**
     * Liveness heartbeat: refreshes the device last_seen_at and returns the
     * trusted ids plus the current state. Revoked/expired credentials are
     * rejected by the token filter before this handler runs.
     */
    @PostMapping("/heartbeat")
    public ApiResponse<PluginModels.HeartbeatResponse> heartbeat() {
        return ApiResponse.success(plugins.heartbeat(currentPlugin.require()));
    }

    private String remoteAddress(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
