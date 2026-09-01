package com.getjobs.cloud.auth;

public interface AccountEmailSender {
    void sendVerification(String recipient, String token);

    void sendPasswordReset(String recipient, String token);
}
