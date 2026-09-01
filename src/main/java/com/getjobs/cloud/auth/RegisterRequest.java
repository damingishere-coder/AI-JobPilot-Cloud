package com.getjobs.cloud.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(max = 128) String password,
        @Size(max = 256) String inviteCode,
        boolean acceptTerms,
        boolean acceptPrivacy,
        boolean acceptAiDisclosure
) {
    public RegisterRequest {
        email = EmailAddressSupport.normalize(email);
    }
}
