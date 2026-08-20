package com.getjobs.cloud.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthApiModels {
    private AuthApiModels() {
    }

    public record User(
            UUID id,
            String emailMasked,
            UserStatus status,
            UserRole role,
            Instant createdAt
    ) {
        static User from(UserAccount account) {
            return new User(
                    account.id(),
                    EmailAddressSupport.mask(account.email()),
                    account.status(),
                    account.role(),
                    account.createdAt()
            );
        }
    }

    public record Session(Instant expiresAt) {
    }

    public record AuthPayload(User user, Session session, String csrfToken) {
    }

    public record CsrfPayload(String csrfToken) {
    }

    public record LogoutPayload(boolean loggedOut) {
    }

    public record Profile(String displayName, String city, String timezone, String locale) {
        static Profile from(UserProfile profile) {
            return new Profile(profile.displayName(), profile.city(), profile.timezone(), profile.locale());
        }
    }

    public record MePayload(
            UUID id,
            String emailMasked,
            UserStatus status,
            UserRole role,
            Profile profile,
            List<Object> quotaSummary,
            Instant sessionExpiresAt,
            String csrfToken
    ) {
    }
}
