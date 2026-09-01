package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@Profile("api")
public class AccountController {
    private final CurrentUser currentUser;
    private final AccountDeletionService deletions;
    private final SessionAuthManager sessions;
    private final SessionRevocationService sessionRevocation;

    public AccountController(
            CurrentUser currentUser,
            AccountDeletionService deletions,
            SessionAuthManager sessions,
            SessionRevocationService sessionRevocation
    ) {
        this.currentUser = currentUser;
        this.deletions = deletions;
        this.sessions = sessions;
        this.sessionRevocation = sessionRevocation;
    }

    @PostMapping("/api/account/deletion")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<DeletionPayload>> deleteAccount(
            @Valid @RequestBody AccountDeletionRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID userId = currentUser.require().userId();
        AccountDeletionRepository.DeletionRequest deletion = deletions.request(
                userId,
                body.password(),
                body.confirmation(),
                idempotencyKey,
                RequestMetadata.from(request)
        );
        sessions.logout(request, response);
        sessionRevocation.revokeAll(userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                new DeletionPayload(deletion.id(), deletion.status(), deletion.requestedAt())
        ));
    }

    public record DeletionPayload(UUID requestId, String status, Instant requestedAt) {
    }
}
