package com.getjobs.cloud.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

@ConfigurationProperties(prefix = "app.auth.email")
@Profile("api")
public class TencentSesProperties {
    private String provider = "disabled";
    private String region = "ap-guangzhou";
    private String fromAddress = "noreply@toudiniuma.cn";
    private String secretId = "";
    private String secretKey = "";
    private long verificationTemplateId;
    private long passwordResetTemplateId;
    private String applicationName = "投递牛马";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String secretId) { this.secretId = secretId; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public long getVerificationTemplateId() { return verificationTemplateId; }
    public void setVerificationTemplateId(long verificationTemplateId) { this.verificationTemplateId = verificationTemplateId; }
    public long getPasswordResetTemplateId() { return passwordResetTemplateId; }
    public void setPasswordResetTemplateId(long passwordResetTemplateId) { this.passwordResetTemplateId = passwordResetTemplateId; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
}
