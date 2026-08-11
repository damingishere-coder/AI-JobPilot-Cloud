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
        request.getSession(true).setMaxInactiveInterval(Math.toIntExact(properties.getPreAuthSessionTimeout().toSeconds()));
        return ApiResponse.success(new AuthApiModels.CsrfPayload(token.getToken()));
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<ApiResponse<AuthApiModels.AuthPayload>> register(
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthResult result = authService.register(
                body.email(), body.password(), body.acceptTerms(), RequestMetadata.from(request)
        );
        SessionAuthManager.SessionState session = sessions.establish(result.principal(), false, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(authPayload(result, session)));
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

    @GetMapping("/api/me")
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
