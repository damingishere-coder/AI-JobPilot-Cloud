package com.getjobs.cloud.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailTokenRequest(
        @NotBlank @Size(max = 512) String token
) {
}
