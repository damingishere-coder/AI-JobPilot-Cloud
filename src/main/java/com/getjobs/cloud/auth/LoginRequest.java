package com.getjobs.cloud.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(max = 128) String password,
        boolean rememberMe
) {
    public LoginRequest {
        email = EmailAddressSupport.normalize(email);
    }
}
