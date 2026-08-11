package com.getjobs.cloud.auth;

public record AuthResult(UserAccount account, SessionPrincipal principal) {
}
