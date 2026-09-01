package com.getjobs.cloud.auth;

import com.getjobs.cloud.plugin.PluginProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitedBetaLaunchGuardTest {
    @Test
    void disabledGuardKeepsLocalDevelopmentDefaults() {
        assertThatCode(() -> new LimitedBetaLaunchGuard(
                false, new AuthProperties(), new TencentSesProperties(), new PluginProperties(),
                new AccountDeletionProperties()
        ).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void enabledGuardRejectsDraftOrIncompleteProductionSettings() {
        assertThatThrownBy(() -> new LimitedBetaLaunchGuard(
                true, new AuthProperties(), new TencentSesProperties(), new PluginProperties(),
                new AccountDeletionProperties()
        ).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_INVITE_REQUIRED=true")
                .hasMessageContaining("三份正式生效日期版本号");
    }

    @Test
    void enabledGuardAcceptsTheExactLimitedBetaBoundary() {
        AuthProperties auth = new AuthProperties();
        auth.setInviteRequired(true);
        auth.setEmailVerificationRequired(true);
        auth.setLegalDocumentsFinalized(true);
        auth.setSecureCookie(true);
        auth.setBetaMaxUsers(10);
        auth.setPublicBaseUrl("https://toudiniuma.cn");
        auth.setTermsVersion("2026-09-01");
        auth.setPrivacyVersion("2026-09-01");
        auth.setAiDisclosureVersion("2026-09-01");
        TencentSesProperties email = new TencentSesProperties();
        email.setProvider("tencent-ses");
        PluginProperties plugin = new PluginProperties();
        plugin.setMinExtensionVersion("1.6.0");

        assertThatCode(() -> new LimitedBetaLaunchGuard(
                true, auth, email, plugin, new AccountDeletionProperties()
        ).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void enabledGuardRejectsDisabledDeletionWorker() {
        AuthProperties auth = new AuthProperties();
        auth.setInviteRequired(true);
        auth.setEmailVerificationRequired(true);
        auth.setLegalDocumentsFinalized(true);
        auth.setSecureCookie(true);
        auth.setBetaMaxUsers(10);
        auth.setPublicBaseUrl("https://toudiniuma.cn");
        auth.setTermsVersion("2026-09-01");
        auth.setPrivacyVersion("2026-09-01");
        auth.setAiDisclosureVersion("2026-09-01");
        TencentSesProperties email = new TencentSesProperties();
        email.setProvider("tencent-ses");
        PluginProperties plugin = new PluginProperties();
        plugin.setMinExtensionVersion("1.6.0");
        AccountDeletionProperties deletion = new AccountDeletionProperties();
        deletion.setWorkerEnabled(false);

        assertThatThrownBy(() -> new LimitedBetaLaunchGuard(
                true, auth, email, plugin, deletion
        ).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_DELETION_WORKER_ENABLED=true");
    }
}
