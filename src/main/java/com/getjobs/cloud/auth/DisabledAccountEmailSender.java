package com.getjobs.cloud.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("api")
@ConditionalOnProperty(name = "app.auth.email.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledAccountEmailSender implements AccountEmailSender {
    private static final Logger log = LoggerFactory.getLogger(DisabledAccountEmailSender.class);

    @Override
    public void sendVerification(String recipient, String token) {
        log.warn("账号验证邮件未发送：邮件提供方尚未启用");
    }

    @Override
    public void sendPasswordReset(String recipient, String token) {
        log.warn("密码重置邮件未发送：邮件提供方尚未启用");
    }
}
