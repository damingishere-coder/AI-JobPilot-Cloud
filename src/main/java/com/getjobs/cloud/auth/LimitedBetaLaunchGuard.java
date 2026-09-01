package com.getjobs.cloud.auth;

import com.getjobs.cloud.plugin.PluginProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
@Profile("api")
public class LimitedBetaLaunchGuard implements InitializingBean {
    private static final String PRODUCTION_URL = "https://toudiniuma.cn";
    private static final String EXTENSION_ORIGIN = "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc";

    private final boolean enabled;
    private final AuthProperties auth;
    private final TencentSesProperties email;
    private final PluginProperties plugin;
    private final AccountDeletionProperties deletion;

    public LimitedBetaLaunchGuard(
            @Value("${app.launch.limited-beta-enabled:false}") boolean enabled,
            AuthProperties auth,
            TencentSesProperties email,
            PluginProperties plugin,
            AccountDeletionProperties deletion
    ) {
        this.enabled = enabled;
        this.auth = auth;
        this.email = email;
        this.plugin = plugin;
        this.deletion = deletion;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!auth.isInviteRequired()) missing.add("AUTH_INVITE_REQUIRED=true");
        if (!auth.isEmailVerificationRequired()) missing.add("AUTH_EMAIL_VERIFICATION_REQUIRED=true");
        if (!auth.isLegalDocumentsFinalized()) missing.add("AUTH_LEGAL_DOCUMENTS_FINALIZED=true");
        if (!auth.isSecureCookie()) missing.add("AUTH_COOKIE_SECURE=true");
        if (auth.getBetaMaxUsers() < 1 || auth.getBetaMaxUsers() > 10) missing.add("BETA_MAX_USERS=1..10");
        if (!PRODUCTION_URL.equals(auth.getPublicBaseUrl())) missing.add("APP_PUBLIC_URL=" + PRODUCTION_URL);
        if (!"tencent-ses".equals(email.getProvider())) missing.add("AUTH_EMAIL_PROVIDER=tencent-ses");
        if (isDraft(auth.getTermsVersion()) || isDraft(auth.getPrivacyVersion()) || isDraft(auth.getAiDisclosureVersion())) {
            missing.add("三份正式生效日期版本号");
        }
        if (!plugin.getAllowedExtensionOrigins().equals(List.of(EXTENSION_ORIGIN))) {
            missing.add("精确的插件 Origin 白名单");
        }
        if (!"1.6.0".equals(plugin.getMinExtensionVersion())) {
            missing.add("PLUGIN_MIN_EXTENSION_VERSION=1.6.0");
        }
        if (!deletion.isWorkerEnabled()) missing.add("ACCOUNT_DELETION_WORKER_ENABLED=true");
        if (deletion.getBackupRetention() == null
                || deletion.getBackupRetention().compareTo(Duration.ofDays(180)) < 0) {
            missing.add("ACCOUNT_DELETION_BACKUP_RETENTION>=180d");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("小范围上线门禁未满足：" + String.join("；", missing));
        }
    }

    private boolean isDraft(String version) {
        return version == null || version.isBlank() || version.toLowerCase(Locale.ROOT).contains("draft");
    }
}
