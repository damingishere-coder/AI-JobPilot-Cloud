package com.getjobs.cloud.auth;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record SessionPrincipal(
        UUID userId,
        String emailMasked,
        UserRole role
) implements Serializable, AuthenticatedPrincipal {
    @Serial
    private static final long serialVersionUID = 1L;

    public List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
