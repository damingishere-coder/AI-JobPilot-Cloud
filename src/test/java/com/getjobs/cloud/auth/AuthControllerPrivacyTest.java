package com.getjobs.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerPrivacyTest {
    @Test
    void passwordResetAndVerificationRequestsReturnTheSamePublicResultForUnknownAndKnownAccounts() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        AuthController controller = new AuthController(
                auth,
                mock(AuthRateLimiter.class),
                mock(SessionAuthManager.class),
                mock(AuditLogService.class),
                new AuthProperties(),
                mock(CurrentUser.class)
        );

        when(auth.requestPasswordReset(eq("unknown@example.com"), any())).thenReturn(Optional.empty());
        assertThat(controller.requestPasswordReset(
                new EmailAddressRequest("unknown@example.com"), request
        ).data().accepted()).isTrue();

        AuthService.PendingEmail reset = new AuthService.PendingEmail("known@example.com", "secret-reset-token");
        when(auth.requestPasswordReset(eq("known@example.com"), any())).thenReturn(Optional.of(reset));
        assertThat(controller.requestPasswordReset(
                new EmailAddressRequest("known@example.com"), request
        ).data().accepted()).isTrue();
        verify(auth).sendPasswordReset(reset);

        when(auth.requestEmailVerification(eq("missing@example.com"), any())).thenReturn(Optional.empty());
        assertThat(controller.requestEmailVerification(
                new EmailAddressRequest("missing@example.com"), request
        ).data().accepted()).isTrue();

        AuthService.PendingEmail verification = new AuthService.PendingEmail(
                "pending@example.com", "secret-verification-token"
        );
        when(auth.requestEmailVerification(eq("pending@example.com"), any())).thenReturn(Optional.of(verification));
        assertThat(controller.requestEmailVerification(
                new EmailAddressRequest("pending@example.com"), request
        ).data().accepted()).isTrue();
        verify(auth).sendVerification(verification);
    }

    @Test
    void limitedBetaDefaultsUseTenSeatsAndShortLivedOneTimeLinks() {
        AuthProperties properties = new AuthProperties();
        assertThat(properties.getBetaMaxUsers()).isEqualTo(10);
        assertThat(properties.getEmailVerificationTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getPasswordResetTtl()).isEqualTo(Duration.ofMinutes(30));
    }
}
