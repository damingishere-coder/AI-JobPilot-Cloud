package com.getjobs.cloud.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountDeletionRequest(
        @NotNull @Size(max = 128) String password,
        @NotBlank @Size(max = 16) String confirmation
) {
}
