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
                "{\"password\":\"json-secret\",\"access_key\":\"access-value\"}"
        );

        assertThat(sanitized)
                .doesNotContain("json-secret", "access-value")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"access_key\":\"[REDACTED]\"");
    }
}
