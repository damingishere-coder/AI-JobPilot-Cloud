package com.getjobs.cloud.resume;

import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.CurrentUser;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.SessionPrincipal;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.ApiResponse;
import com.getjobs.cloud.web.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/resumes")
@Profile("api")
public class ResumeController {
    private final CurrentUser currentUser;
    private final ResumeService resumes;
    private final AuditLogService auditLogs;

    public ResumeController(CurrentUser currentUser, ResumeService resumes, AuditLogService auditLogs) {
        this.currentUser = currentUser;
        this.resumes = resumes;
        this.auditLogs = auditLogs;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ResumeApiModels.UploadPayload>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "setCurrent", defaultValue = "true") boolean setCurrent,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request
    ) {
        SessionPrincipal principal = currentUser.require();
        RequestMetadata metadata = RequestMetadata.from(request);
        try {
            ResumeService.UploadOutcome outcome = resumes.upload(
                    principal.userId(), file, setCurrent, idempotencyKey
            );
            auditLogs.append(
                    principal.userId(), principal.role(), "RESUME_UPLOAD", "RESUME",
                    outcome.resume().id(), "SUCCESS", metadata,
                    Map.of(
                            "contentType", outcome.resume().contentType(),
                            "fileSize", outcome.resume().fileSize(),
                            "deduplicated", outcome.deduplicated()
                    )
            );
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                    new ResumeApiModels.UploadPayload(outcome.resume(), outcome.deduplicated())
            ));
        } catch (ApiException exception) {
            auditLogs.append(
                    principal.userId(), principal.role(), "RESUME_UPLOAD_REJECTED", "RESUME",
                    null, "DENIED", metadata, Map.of("reason", exception.code())
            );
            throw exception;
        }
    }

    @GetMapping
    public ApiResponse<PageResult<ResumeApiModels.ResumeView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(resumes.list(currentUser.require().userId(), page, size));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<ResumeApiModels.ResumeView>> current(
            @RequestParam(defaultValue = "false") boolean includeExtractedText
    ) {
        ResumeApiModels.ResumeView current = resumes.current(
                currentUser.require().userId(), includeExtractedText
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(current));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ResumeApiModels.DeletePayload>> delete(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request
    ) {
        SessionPrincipal principal = currentUser.require();
        validateIdempotencyKey(idempotencyKey);
        int version = parseVersion(ifMatch);
        ResumeApiModels.DeletePayload deleted = resumes.delete(principal.userId(), id, version);
        auditLogs.append(
                principal.userId(), principal.role(), "RESUME_DELETE_REQUESTED", "RESUME",
                id, "SUCCESS", RequestMetadata.from(request), Map.of("version", version)
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(deleted));
    }

    private int parseVersion(String value) {
        try {
            String normalized = value == null ? "" : value.trim().replace("\"", "");
            int version = Integer.parseInt(normalized);
            if (version <= 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "If-Match 必须是有效的简历版本号");
        }
    }

    private void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Idempotency-Key 不能为空且不能超过 128 个字符"
            );
        }
    }
}
