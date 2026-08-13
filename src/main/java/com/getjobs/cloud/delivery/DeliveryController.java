package com.getjobs.cloud.delivery;

import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.ApiResponse;
import com.getjobs.cloud.web.PageResult;
import com.getjobs.cloud.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web delivery list endpoints: create, list, detail, greeting, confirm, skip.
 * All state changes require the Web session and CSRF; plugin tokens can never
 * reach these endpoints.
 */
@RestController
@RequestMapping("/api/delivery/tasks")
@Profile("api")
public class DeliveryController {
    private final CurrentUser currentUser;
    private final DeliveryService delivery;
    private final AuditLogService auditLogs;

    public DeliveryController(CurrentUser currentUser, DeliveryService delivery, AuditLogService auditLogs) {
        this.currentUser = currentUser;
        this.delivery = delivery;
        this.auditLogs = auditLogs;
    }

    @PostMapping
    public org.springframework.http.ResponseEntity<ApiResponse<DeliveryModels.TaskView>> createTask(
            @RequestBody DeliveryModels.CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.jobPostId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "jobPostId 不能为空");
        }
        DeliveryModels.TaskView result = delivery.createTask(
                principal.userId(), request.jobPostId(), request.jobMatchId(),
                idempotencyKey, MDC.get(RequestIdFilter.MDC_KEY)
        );
        auditLogs.append(
                principal.userId(), principal.role(), "DELIVERY_TASK_CREATED",
                "DELIVERY_TASK", result.id(), "SUCCESS",
                RequestMetadata.from(servletRequest),
                Map.of("jobPostId", result.jobPostId().toString(),
                        "jobMatchId", result.jobMatchId() == null ? "" : result.jobMatchId().toString())
        );
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    @GetMapping
    public ApiResponse<PageResult<DeliveryModels.TaskListItem>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(required = false) String sort
    ) {
        return ApiResponse.success(delivery.list(
                currentUser.require().userId(), page, size, status, platform,
                keyword, createdFrom, createdTo, sort
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeliveryModels.TaskDetail> detail(@PathVariable UUID id) {
        return ApiResponse.success(delivery.detail(currentUser.require().userId(), id));
    }

    @PutMapping("/{id}/greeting")
    public ApiResponse<DeliveryModels.GreetingResult> updateGreeting(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.UpdateGreetingRequest request,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        DeliveryModels.GreetingResult result = delivery.updateGreeting(
                principal.userId(), id, request.version(), request.greeting()
        );
        auditLogs.append(
                principal.userId(), principal.role(), "DELIVERY_GREETING_UPDATED",
                "DELIVERY_TASK", id, "SUCCESS",
                RequestMetadata.from(servletRequest),
                Map.of("confirmationRequired", result.confirmationRequired())
        );
        return ApiResponse.success(result);
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<DeliveryModels.ConfirmResult> confirm(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.ConfirmRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        // The DELIVERY_TASK_CONFIRMED audit runs inside the service transaction
        // together with the real state transition, so idempotent replays can
        // never duplicate it.
        DeliveryModels.ConfirmResult result = delivery.confirm(
                principal.userId(), id, request.version(), request.acknowledged(),
                request.assignedDeviceId(), idempotencyKey, MDC.get(RequestIdFilter.MDC_KEY),
                RequestMetadata.from(servletRequest), principal.role()
        );
        return ApiResponse.success(result);
    }

    @PostMapping("/{id}/skip")
    public ApiResponse<DeliveryModels.SkipResult> skip(
            @PathVariable UUID id,
            @RequestBody DeliveryModels.SkipRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体不能为空");
        }
        // Same in-transaction audit contract as confirm.
        DeliveryModels.SkipResult result = delivery.skip(
                principal.userId(), id, request.version(), request.reason(),
                idempotencyKey, MDC.get(RequestIdFilter.MDC_KEY),
                RequestMetadata.from(servletRequest), principal.role()
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
