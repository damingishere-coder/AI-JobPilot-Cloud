package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("api")
public class CurrentUser {
    public Optional<SessionPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public SessionPrincipal require() {
        return optional().orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录"
        ));
    }
}
