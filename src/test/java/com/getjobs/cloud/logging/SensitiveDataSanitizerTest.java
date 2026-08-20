package com.getjobs.cloud.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {
    @Test
    void redactsBearerAndNamedSecrets() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "Authorization: Bearer abc.def password=hunter2 cookie=session123 api_key=sk-test"
        );

        assertThat(sanitized)
                .doesNotContain("abc.def", "hunter2", "session123", "sk-test")
                .contains("[REDACTED]");
    }

    @Test
    void keepsOrdinaryInfrastructureMessage() {
        assertThat(SensitiveDataSanitizer.sanitize("Redis connection timed out"))
                .isEqualTo("Redis connection timed out");
    }

    @Test
    void redactsQuotedJsonStyleFields() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "{\"password\":\"json-secret\",\"access_key\":\"access-value\","
                        + "\"csrf\":\"csrf-value\",\"email\":\"person@example.com\"}"
        );

        assertThat(sanitized)
                .doesNotContain("json-secret", "access-value", "csrf-value", "person@example.com")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"access_key\":\"[REDACTED]\"");
    }

    @Test
    void redactsJwtTokensEverywhere() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "failed: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.signed-signature-part"
        );

        assertThat(sanitized)
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiJhIn0", "signed-signature-part")
                .contains("[REDACTED]");
    }

    @Test
    void redactsPluginTokensAndTencentSecretIds() {
        String pluginToken = "ajp_plg_" + "c2VjcmV0LXBsdWdpbi10b2tlbg";
        String tencentSecretId = String.join("", "A", "K", "I", "D", "0123456789abcdef");
        String sanitized = SensitiveDataSanitizer.sanitize(
                "token " + pluginToken + " with " + tencentSecretId
        );

        assertThat(sanitized)
                .doesNotContain(pluginToken, tencentSecretId)
                .contains("[REDACTED]");
    }

    @Test
    void redactsSetCookieAndCaseInsensitivePasswdHeaders() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "Set-Cookie: AJP_SESSION=session-cookie-value; Path=/; HttpOnly"
        );

        assertThat(sanitized)
                .doesNotContain("session-cookie-value", "AJP_SESSION=session-cookie-value")
                .contains("Set-Cookie: [REDACTED]");

        String passwd = SensitiveDataSanitizer.sanitize("Passwd=root-credential");
        assertThat(passwd).doesNotContain("root-credential").contains("[REDACTED]");
    }

    @Test
    void redactsDatabaseUrlPasswordsAndLabeledSecretPairs() {
        String tencentSecretId = String.join("", "A", "K", "I", "D", "fedcba9876543210");
        String sanitized = SensitiveDataSanitizer.sanitize(
                "cannot connect jdbc:postgresql://dbuser:db-password-value@db.internal:5432/app "
                        + "SecretId=" + tencentSecretId + " SecretKey=tencent-secret-value"
        );

        assertThat(sanitized)
                .doesNotContain("db-password-value", tencentSecretId, "tencent-secret-value")
                .contains("jdbc:postgresql://dbuser:[REDACTED]@db.internal:5432/app")
                .contains("SecretId=[REDACTED]", "SecretKey=[REDACTED]");
    }

    @Test
    void redactsPhoneNumbers() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "contact 13812345678 or +86 13812345678 or +1-555-010-2345"
        );

        assertThat(sanitized)
                .doesNotContain("13812345678", "+86 13812345678", "+1-555-010-2345")
                .contains("[REDACTED]");
    }

    @Test
    void redactsAccessAndRefreshTokensByLabel() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "{\"access_token\":\"at-123\",\"refresh_token\":\"rt-456\",\"x-api-key\":\"xk-789\"}"
        );

        assertThat(sanitized)
                .doesNotContain("at-123", "rt-456", "xk-789")
                .contains("\"access_token\":\"[REDACTED]\"")
                .contains("\"refresh_token\":\"[REDACTED]\"")
                .contains("\"x-api-key\":\"[REDACTED]\"");
    }

    @Test
    void nullAndBlankInputPassThrough() {
        assertThat(SensitiveDataSanitizer.sanitize(null)).isNull();
        assertThat(SensitiveDataSanitizer.sanitize("   ")).isEqualTo("   ");
    }
}
