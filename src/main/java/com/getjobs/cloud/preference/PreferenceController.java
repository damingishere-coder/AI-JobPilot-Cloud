package com.getjobs.cloud.preference;

import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/preferences")
@Profile("api")
public class PreferenceController {
    private final CurrentUser currentUser;
    private final PreferenceService preferences;
    private final AuditLogService auditLogs;

    public PreferenceController(CurrentUser currentUser, PreferenceService preferences, AuditLogService auditLogs) {
        this.currentUser = currentUser;
        this.preferences = preferences;
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PreferenceModels.PreferenceView>> current() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(preferences.current(currentUser.require().userId())));
    }

    @PutMapping
    public ApiResponse<PreferenceModels.PreferenceView> update(
            @RequestBody PreferenceModels.UpdateRequest request,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        PreferenceModels.PreferenceView updated = preferences.update(principal.userId(), request);
        auditLogs.append(
                principal.userId(), principal.role(), "PREFERENCE_UPDATED", "JOB_PREFERENCE",
                updated.id(), "SUCCESS", RequestMetadata.from(servletRequest),
                Map.of("version", updated.version())
        );
        return ApiResponse.success(updated);
    }
}
