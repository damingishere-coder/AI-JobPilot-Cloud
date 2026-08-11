package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPasswordWithoutFixedCharacterCompositionRule() {
        assertThatCode(() -> policy.validate("这是一段足够长的安全口令", "person@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCommonControlCharacterAndEmailLikePasswords() {
        assertValidationFailure("password1234", "person@example.com");
        assertValidationFailure("StrongPass\n2026", "person@example.com");
        assertValidationFailure("Person-Account!2026", "person.account@example.com");
    }

    @Test
    void normalizesAndMasksEmail() {
        assertThat(EmailAddressSupport.normalize(" User.Name@Example.COM "))
                .isEqualTo("user.name@example.com");
        assertThat(EmailAddressSupport.mask("User.Name@Example.COM"))
                .isEqualTo("us***@example.com");
    }

    private void assertValidationFailure(String password, String email) {
        assertThatThrownBy(() -> policy.validate(password, email))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.fieldErrors()).extracting("field").containsExactly("password");
                });
    }
}
