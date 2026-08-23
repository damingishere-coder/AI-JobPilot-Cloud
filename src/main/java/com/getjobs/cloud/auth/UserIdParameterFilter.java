package com.getjobs.cloud.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile("api")
public class UserIdParameterFilter extends OncePerRequestFilter {
    private final SecurityResponseWriter responses;

    public UserIdParameterFilter(SecurityResponseWriter responses) {
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean containsUserId = request.getParameterMap().keySet().stream()
                .map(name -> name.replace("_", "").replace("-", ""))
                .anyMatch("userid"::equalsIgnoreCase);
        if (containsUserId) {
            responses.write(
                    response,
                    400,
                    "USER_ID_NOT_ALLOWED",
                    "用户身份只能从当前 Session 获取",
                    false
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
