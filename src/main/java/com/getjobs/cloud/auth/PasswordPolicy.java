package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiError;
import com.getjobs.cloud.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Profile("api")
public class PasswordPolicy {
    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456789012", "password1234", "password123!", "qwertyuiop12",
            "qwerty123456", "abc123456789", "administrator", "admin1234567",
            "iloveyou1234", "welcome12345", "letmein123456", "monkey123456",
            "dragon123456", "football1234", "baseball1234", "sunshine1234",
            "princess1234", "trustnoone123", "changeme1234", "passw0rd1234",
            "woaini123456", "zhangsan1234", "wangxiaoming", "111111111111",
            "000000000000", "aaaaaaaaaaaa", "abcdefghijkl", "qwertyqwerty"
    );

    public void validate(String password, String normalizedEmail) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            reject("密码长度必须为 12–128 个字符");
        }
        if (password.codePoints().anyMatch(Character::isISOControl)) {
            reject("密码不能包含控制字符");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String email = EmailAddressSupport.normalize(normalizedEmail);
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String comparablePassword = lower.replaceAll("[^\\p{L}\\p{N}]", "");
        String comparableLocalPart = localPart.replaceAll("[^\\p{L}\\p{N}]", "");
        boolean resemblesEmail = lower.equals(email)
                || (!comparableLocalPart.isBlank()
                    && (comparablePassword.equals(comparableLocalPart)
                        || (comparableLocalPart.length() >= 6 && comparablePassword.contains(comparableLocalPart))));
        if (COMMON_PASSWORDS.contains(lower) || resemblesEmail) {
            reject("该密码过于常见或与邮箱相同，请更换密码");
        }
    }

    private void reject(String reason) {
        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数不正确",
                false,
                0,
                List.of(new ApiError.FieldViolation("password", reason))
        );
    }
}
