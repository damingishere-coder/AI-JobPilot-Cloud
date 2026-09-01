package com.getjobs.cloud.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Template;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("api")
@ConditionalOnProperty(name = "app.auth.email.provider", havingValue = "tencent-ses")
public class TencentSesAccountEmailSender implements AccountEmailSender {
    private final TencentSesProperties properties;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public TencentSesAccountEmailSender(
            TencentSesProperties properties,
            AuthProperties authProperties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        requireConfigured();
    }

    @Override
    public void sendVerification(String recipient, String token) {
        send(
                recipient,
                "验证你的投递牛马邮箱",
                properties.getVerificationTemplateId(),
                Map.of(
                        "applicationName", properties.getApplicationName(),
                        "actionUrl", actionUrl("/verify-email", token),
                        "expiresMinutes", authProperties.getEmailVerificationTtl().toMinutes()
                )
        );
    }

    @Override
    public void sendPasswordReset(String recipient, String token) {
        send(
                recipient,
                "重置你的投递牛马密码",
                properties.getPasswordResetTemplateId(),
                Map.of(
                        "applicationName", properties.getApplicationName(),
                        "actionUrl", actionUrl("/reset-password", token),
                        "expiresMinutes", authProperties.getPasswordResetTtl().toMinutes()
                )
        );
    }

    private void send(String recipient, String subject, long templateId, Map<String, ?> data) {
        try {
            SendEmailRequest request = new SendEmailRequest();
            request.setFromEmailAddress(properties.getFromAddress());
            request.setDestination(new String[]{recipient});
            request.setSubject(subject);
            Template template = new Template();
            template.setTemplateID(templateId);
            template.setTemplateData(objectMapper.writeValueAsString(data));
            request.setTemplate(template);
            client().SendEmail(request);
        } catch (TencentCloudSDKException | JsonProcessingException exception) {
            throw new EmailDeliveryException("腾讯云邮件发送失败", exception);
        }
    }

    private SesClient client() {
        Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("ses.tencentcloudapi.com");
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new SesClient(credential, properties.getRegion(), clientProfile);
    }

    private String actionUrl(String path, String token) {
        String base = authProperties.getPublicBaseUrl().replaceAll("/+$", "");
        return base + path + "?token=" + token;
    }

    private void requireConfigured() {
        if (properties.getSecretId().isBlank() || properties.getSecretKey().isBlank()
                || properties.getFromAddress().isBlank()
                || properties.getVerificationTemplateId() <= 0
                || properties.getPasswordResetTemplateId() <= 0) {
            throw new IllegalStateException("启用腾讯云 SES 前必须配置发信地址、模板和 Secret");
        }
    }
}
