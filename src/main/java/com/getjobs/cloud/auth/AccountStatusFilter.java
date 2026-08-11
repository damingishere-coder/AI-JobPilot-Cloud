package com.getjobs.cloud.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@Profile("api")
public class AccountStatusFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final SecurityResponseWriter responses;
    private final SessionRevocationService sessionRevocation;

    public AccountStatusFilter(
            UserRepository users,
            SecurityResponseWriter responses,
            SessionRevocationService sessionRevocation
    ) {
        this.users = users;
        this.responses = responses;
        this.sessionRevocation = sessionRevocation;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            Optional<UserAccount> account = users.findById(principal.userId());
            if (account.isEmpty() || account.get().status() != UserStatus.ACTIVE) {
                sessionRevocation.revokeAll(principal.userId());
                if (request.getSession(false) != null) {
                    request.getSession(false).invalidate();
                }
                SecurityContextHolder.clearContext();
                boolean locked = account.map(UserAccount::status).orElse(UserStatus.DISABLED) == UserStatus.LOCKED;
                responses.write(
                        response,
                        403,
                        locked ? "ACCOUNT_LOCKED" : "ACCOUNT_DISABLED",
                        locked ? "账号已被临时锁定" : "账号当前不可用",
                        false
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
