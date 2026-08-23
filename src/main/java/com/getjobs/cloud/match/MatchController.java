package com.getjobs.cloud.match;

import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.ratelimit.RateLimitProperties;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@Profile("api")
public class MatchController {
    private static final String ANALYZE_KEY_PREFIX = "ai-jobpilot:api:rate:match-analyze:user:";
    private static final String BATCH_KEY_PREFIX = "ai-jobpilot:api:rate:match-batch:user:";

    private final CurrentUser currentUser;
    private final MatchService matches;
    private final AuditLogService auditLogs;
    private final ApiRateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public MatchController(
            CurrentUser currentUser,
            MatchService matches,
            AuditLogService auditLogs,
            ApiRateLimiter rateLimiter,
            RateLimitProperties rateLimits
    ) {
        this.currentUser = currentUser;
        this.matches = matches;
        this.auditLogs = auditLogs;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/{id}/analyze")
    public ApiResponse<MatchModels.QueuedResult> analyze(
            @PathVariable UUID id,
            @RequestBody(required = false) MatchModels.AnalyzeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        rateLimiter.check(
                ANALYZE_KEY_PREFIX + principal.userId(),
                rateLimits.getMatchAnalyzeLimit(),
                rateLimits.getMatchAnalyzeWindow()
        );
        boolean force = request != null && request.force();
        MatchModels.QueuedResult result = matches.analyze(
                principal.userId(), id, force
        );
        if (result.reusedExisting()) {
            auditLogs.append(
                    principal.userId(), principal.role(), "JOB_ANALYSIS_REUSED",
                    "JOB_MATCH", result.matchId(), "SUCCESS",
                    RequestMetadata.from(servletRequest),
                    Map.of("jobId", id.toString(), "status", result.status(),
                            "force", force)
            );
        } else {
            auditLogs.append(
                    principal.userId(), principal.role(), "JOB_ANALYSIS_REQUESTED",
                    "JOB_MATCH", result.matchId(), "SUCCESS",
                    RequestMetadata.from(servletRequest),
                    Map.of("jobId", id.toString(), "status", result.status(),
                            "force", force)
            );
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/batch-analyze")
    public ApiResponse<MatchModels.BatchResult> batchAnalyze(
            @RequestBody MatchModels.BatchAnalyzeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        rateLimiter.check(
                BATCH_KEY_PREFIX + principal.userId(),
                rateLimits.getMatchBatchLimit(),
                rateLimits.getMatchBatchWindow()
        );
        MatchModels.BatchResult result = matches.batchAnalyze(
                principal.userId(),
                request.jobIds(),
                request.force()
        );
        // Write audit for each accepted match; skip rejected to avoid leaking
        RequestMetadata metadata = RequestMetadata.from(servletRequest);
        for (MatchModels.QueuedResult accepted : result.accepted()) {
            String action = accepted.reusedExisting()
                    ? "JOB_ANALYSIS_REUSED" : "JOB_ANALYSIS_REQUESTED";
            auditLogs.append(
                    principal.userId(), principal.role(), action,
                    "JOB_MATCH", accepted.matchId(), "SUCCESS",
                    metadata,
                    Map.of("jobId", accepted.jobId().toString(),
                            "status", accepted.status())
            );
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}/match")
    public ApiResponse<MatchModels.MatchView> getMatch(@PathVariable UUID id) {
        return ApiResponse.success(matches.getMatch(
                currentUser.require().userId(), id
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
