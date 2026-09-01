package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
public class AuthController {
    private final AuthService authService;
    private final AuthRateLimiter rateLimiter;
    private final SessionAuthManager sessions;
    private final AuditLogService auditLogs;
    private final AuthProperties properties;
    private final CurrentUser currentUser;

    public AuthController(
            AuthService authService,
            AuthRateLimiter rateLimiter,
            SessionAuthManager sessions,
            AuditLogService auditLogs,
            AuthProperties properties,
            CurrentUser currentUser
    ) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.auditLogs = auditLogs;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/auth/csrf")
    public ApiResponse<AuthApiModels.CsrfPayload> csrf(CsrfToken token, HttpServletRequest request) {
        RequestMetadata metadata = RequestMetadata.from(request);
        rateLimiter.checkCsrf(metadata.remoteAddress());
        // Bound only fresh anonymous sessions: token refreshes on an existing
        // (authenticated) session must not shorten its configured lifetime.
        HttpSession session = request.getSession(true);
        if (session.isNew()) {
            session.setMaxInactiveInterval(Math.toIntExact(properties.getPreAuthSessionTimeout().toSeconds()));
        }
        return ApiResponse.success(new AuthApiModels.CsrfPayload(token.getToken()));
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<ApiResponse<?>> register(
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthService.RegistrationOutcome outcome = authService.register(
                body.email(), body.password(), body.inviteCode(),
                body.acceptTerms(), body.acceptPrivacy(), body.acceptAiDisclosure(),
                RequestMetadata.from(request)
        );
        if (outcome.verificationRequired()) {
            authService.sendVerification(outcome.pendingEmail());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                    new AuthApiModels.EmailActionPayload(
                            true, true, EmailAddressSupport.mask(body.email())
                    )
            ));
        }
        AuthResult result = outcome.authResult();
        SessionAuthManager.SessionState session = sessions.establish(result.principal(), false, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(authPayload(result, session)));
    }

    @PostMapping("/api/auth/email-verification/request")
    public ApiResponse<AuthApiModels.ActionPayload> requestEmailVerification(
            @Valid @RequestBody EmailAddressRequest body,
            HttpServletRequest request
    ) {
        authService.requestEmailVerification(body.email(), RequestMetadata.from(request))
                .ifPresent(authService::sendVerification);
        return ApiResponse.success(new AuthApiModels.ActionPayload(true));
    }

    @PostMapping("/api/auth/email-verification/confirm")
    public ApiResponse<AuthApiModels.ActionPayload> confirmEmailVerification(
            @Valid @RequestBody EmailTokenRequest body,
            HttpServletRequest request
    ) {
        authService.verifyEmail(body.token(), RequestMetadata.from(request));
        return ApiResponse.success(new AuthApiModels.ActionPayload(true));
    }

    @PostMapping("/api/auth/password-reset/request")
    public ApiResponse<AuthApiModels.ActionPayload> requestPasswordReset(
            @Valid @RequestBody EmailAddressRequest body,
            HttpServletRequest request
    ) {
        authService.requestPasswordReset(body.email(), RequestMetadata.from(request))
                .ifPresent(authService::sendPasswordReset);
        return ApiResponse.success(new AuthApiModels.ActionPayload(true));
    }

    @PostMapping("/api/auth/password-reset/confirm")
    public ApiResponse<AuthApiModels.ActionPayload> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest body,
            HttpServletRequest request
    ) {
        authService.resetPassword(body.token(), body.newPassword(), RequestMetadata.from(request));
        return ApiResponse.success(new AuthApiModels.ActionPayload(true));
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<AuthApiModels.AuthPayload> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthResult result = authService.login(body.email(), body.password(), RequestMetadata.from(request));
        SessionAuthManager.SessionState session = sessions.establish(result.principal(), body.rememberMe(), request, response);
        return ApiResponse.success(authPayload(result, session));
    }

    @PostMapping("/api/auth/logout")
    @Transactional
    public ApiResponse<AuthApiModels.LogoutPayload> logout(
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (principal != null) {
            auditLogs.append(
                    principal.userId(),
                    principal.role(),
                    "AUTH_LOGOUT",
                    "SUCCESS",
                    RequestMetadata.from(request),
                    Map.of()
            );
        }
        sessions.logout(request, response);
        return ApiResponse.success(new AuthApiModels.LogoutPayload(true));
    }

    /**
     * Session self-check. POST (not GET) so the request goes through Spring
     * Security's {@code CsrfFilter} instead of relying on a hand-written token
     * comparison; the session chain may read/refresh session state on access.
     */
    @PostMapping("/api/me")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ApiResponse<AuthApiModels.MePayload> me(
            CsrfToken csrfToken,
            HttpServletRequest request
    ) {
        SessionPrincipal principal = currentUser.require();
        AuthService.CurrentUserView current = authService.currentUser(principal.userId());
        HttpSession session = request.getSession(false);
        Instant expiresAt = session == null
                ? Instant.now()
                : Instant.ofEpochMilli(session.getLastAccessedTime()).plusSeconds(session.getMaxInactiveInterval());
        return ApiResponse.success(new AuthApiModels.MePayload(
                current.account().id(),
                EmailAddressSupport.mask(current.account().email()),
                current.account().status(),
                current.account().role(),
                AuthApiModels.Profile.from(current.profile()),
                List.of(),
                expiresAt,
                csrfToken.getToken()
        ));
    }

    private AuthApiModels.AuthPayload authPayload(
            AuthResult result,
            SessionAuthManager.SessionState session
    ) {
        return new AuthApiModels.AuthPayload(
                AuthApiModels.User.from(result.account()),
                new AuthApiModels.Session(session.expiresAt()),
                session.csrfToken()
        );
    }
}
