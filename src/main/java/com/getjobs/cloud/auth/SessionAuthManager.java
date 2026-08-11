package com.getjobs.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@Profile("api")
public class SessionAuthManager {
    private final AuthProperties properties;
    private final SecurityContextRepository securityContexts;
    private final CsrfTokenRepository csrfTokens;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthManager(
            AuthProperties properties,
            SecurityContextRepository securityContexts,
            CsrfTokenRepository csrfTokens
    ) {
        this.properties = properties;
        this.securityContexts = securityContexts;
        this.csrfTokens = csrfTokens;
    }

    public SessionState establish(
            SessionPrincipal principal,
            boolean rememberMe,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Duration timeout = rememberMe ? properties.getRememberedSessionTimeout() : properties.getNormalSessionTimeout();
        request.setAttribute(
                DynamicSessionCookieSerializer.COOKIE_MAX_AGE_ATTRIBUTE,
                rememberMe ? Math.toIntExact(timeout.toSeconds()) : -1
        );
        HttpSession session = request.getSession(true);
        if (!session.isNew()) {
            request.changeSessionId();
        }
        session.setMaxInactiveInterval(Math.toIntExact(timeout.toSeconds()));

        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.authorities()
        );
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);

        csrfTokens.saveToken(null, request, response);
        CsrfToken csrfToken = csrfTokens.generateToken(request);
        csrfTokens.saveToken(csrfToken, request, response);
        return new SessionState(Instant.now().plus(timeout), csrfToken.getToken());
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, contextHolder.getContext().getAuthentication());
        contextHolder.clearContext();
    }

    public record SessionState(Instant expiresAt, String csrfToken) {
    }
}
