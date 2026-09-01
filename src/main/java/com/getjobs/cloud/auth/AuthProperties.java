package com.getjobs.cloud.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@Profile("api")
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    @NotBlank
    @Size(min = 32)
    private String hashPepper;
    @NotBlank
    private String termsVersion = "2026-08-draft";
    @NotBlank
    private String privacyVersion = "2026-08-draft";
    @NotBlank
    private String aiDisclosureVersion = "2026-08-draft";
    private boolean legalDocumentsFinalized;
    private boolean inviteRequired;
    private boolean emailVerificationRequired;
    @Positive
    private int betaMaxUsers = 10;
    @NotNull
    private Duration emailVerificationTtl = Duration.ofHours(24);
    @NotNull
    private Duration passwordResetTtl = Duration.ofMinutes(30);
    @NotBlank
    private String publicBaseUrl = "http://localhost:8080";
    @NotBlank
    private String cookieName = "AJP_SESSION";
    private boolean secureCookie;
    @NotNull
    private Duration normalSessionTimeout = Duration.ofHours(12);
    @NotNull
    private Duration rememberedSessionTimeout = Duration.ofDays(30);
    @NotNull
    private Duration preAuthSessionTimeout = Duration.ofMinutes(10);
    @Positive
    private int loginIpLimit = 10;
    @Positive
    private int loginEmailLimit = 5;
    @NotNull
    private Duration loginIpWindow = Duration.ofMinutes(1);
    @NotNull
    private Duration loginEmailWindow = Duration.ofMinutes(15);
    @Positive
    private int registerIpLimit = 5;
    @NotNull
    private Duration registerIpWindow = Duration.ofHours(1);
    @Positive
    private int csrfIpLimit = 30;
    @NotNull
    private Duration csrfIpWindow = Duration.ofMinutes(1);

    public String getHashPepper() { return hashPepper; }
    public void setHashPepper(String hashPepper) { this.hashPepper = hashPepper; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }
    public String getPrivacyVersion() { return privacyVersion; }
    public void setPrivacyVersion(String privacyVersion) { this.privacyVersion = privacyVersion; }
    public String getAiDisclosureVersion() { return aiDisclosureVersion; }
    public void setAiDisclosureVersion(String aiDisclosureVersion) { this.aiDisclosureVersion = aiDisclosureVersion; }
    public boolean isLegalDocumentsFinalized() { return legalDocumentsFinalized; }
    public void setLegalDocumentsFinalized(boolean legalDocumentsFinalized) { this.legalDocumentsFinalized = legalDocumentsFinalized; }
    public boolean isInviteRequired() { return inviteRequired; }
    public void setInviteRequired(boolean inviteRequired) { this.inviteRequired = inviteRequired; }
    public boolean isEmailVerificationRequired() { return emailVerificationRequired; }
    public void setEmailVerificationRequired(boolean emailVerificationRequired) { this.emailVerificationRequired = emailVerificationRequired; }
    public int getBetaMaxUsers() { return betaMaxUsers; }
    public void setBetaMaxUsers(int betaMaxUsers) { this.betaMaxUsers = betaMaxUsers; }
    public Duration getEmailVerificationTtl() { return emailVerificationTtl; }
    public void setEmailVerificationTtl(Duration emailVerificationTtl) { this.emailVerificationTtl = emailVerificationTtl; }
    public Duration getPasswordResetTtl() { return passwordResetTtl; }
    public void setPasswordResetTtl(Duration passwordResetTtl) { this.passwordResetTtl = passwordResetTtl; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public String getCookieName() { return cookieName; }
    public void setCookieName(String cookieName) { this.cookieName = cookieName; }
    public boolean isSecureCookie() { return secureCookie; }
    public void setSecureCookie(boolean secureCookie) { this.secureCookie = secureCookie; }
    public Duration getNormalSessionTimeout() { return normalSessionTimeout; }
    public void setNormalSessionTimeout(Duration normalSessionTimeout) { this.normalSessionTimeout = normalSessionTimeout; }
    public Duration getRememberedSessionTimeout() { return rememberedSessionTimeout; }
    public void setRememberedSessionTimeout(Duration rememberedSessionTimeout) { this.rememberedSessionTimeout = rememberedSessionTimeout; }
    public Duration getPreAuthSessionTimeout() { return preAuthSessionTimeout; }
    public void setPreAuthSessionTimeout(Duration preAuthSessionTimeout) { this.preAuthSessionTimeout = preAuthSessionTimeout; }
    public int getLoginIpLimit() { return loginIpLimit; }
    public void setLoginIpLimit(int loginIpLimit) { this.loginIpLimit = loginIpLimit; }
    public int getLoginEmailLimit() { return loginEmailLimit; }
    public void setLoginEmailLimit(int loginEmailLimit) { this.loginEmailLimit = loginEmailLimit; }
    public Duration getLoginIpWindow() { return loginIpWindow; }
    public void setLoginIpWindow(Duration loginIpWindow) { this.loginIpWindow = loginIpWindow; }
    public Duration getLoginEmailWindow() { return loginEmailWindow; }
    public void setLoginEmailWindow(Duration loginEmailWindow) { this.loginEmailWindow = loginEmailWindow; }
    public int getRegisterIpLimit() { return registerIpLimit; }
    public void setRegisterIpLimit(int registerIpLimit) { this.registerIpLimit = registerIpLimit; }
    public Duration getRegisterIpWindow() { return registerIpWindow; }
    public void setRegisterIpWindow(Duration registerIpWindow) { this.registerIpWindow = registerIpWindow; }
    public int getCsrfIpLimit() { return csrfIpLimit; }
    public void setCsrfIpLimit(int csrfIpLimit) { this.csrfIpLimit = csrfIpLimit; }
    public Duration getCsrfIpWindow() { return csrfIpWindow; }
    public void setCsrfIpWindow(Duration csrfIpWindow) { this.csrfIpWindow = csrfIpWindow; }
}
