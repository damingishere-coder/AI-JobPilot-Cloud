package com.getjobs.cloud.plugin;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the browser-plugin binding and token infrastructure.
 * No secrets live here: bind codes and token values are generated with
 * CSPRNG at runtime and only their hashes reach the database.
 */
@Validated
@Profile("api")
@ConfigurationProperties(prefix = "app.plugin")
public class PluginProperties {

    /** Lifetime of a one-time bind code (PostgreSQL row + replay cache). */
    private Duration bindCodeTtl = Duration.ofMinutes(5);

    /** Maximum concurrently active bind codes per user. */
    private int maxActiveBindCodes = 3;

    /** Lifetime of a newly issued plugin token. */
    private Duration tokenTtl = Duration.ofDays(90);

    /** Display prefix of opaque tokens, e.g. {@code ajp_plg_}. */
    @NotBlank
    private String tokenPrefix = "ajp_plg_";

    /** Upper bound of ACTIVE devices per user. */
    private int maxDevicesPerUser = 10;

    /** Optional minimum extension version (semver); empty means no minimum. */
    private String minExtensionVersion = "";

    /** Bind attempts per remote IP within {@link #bindIpWindow}. */
    private int bindIpLimit = 20;

    private Duration bindIpWindow = Duration.ofMinutes(10);

    /** Failed attempts allowed against a single bind code. */
    private int bindCodeAttemptLimit = 5;

    /** Minimum interval between last_seen/last_used database writes. */
    private int lastSeenUpdateIntervalSeconds = 60;

    /**
     * Exact extension origins allowed to call the plugin API via CORS.
     * The development default is the fixed development extension ID derived from
     * the committed manifest public key; production values come from the
     * PLUGIN_ALLOWED_EXTENSION_ORIGINS environment variable.
     */
    private List<String> allowedExtensionOrigins = List.of(
            "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc"
    );

    public Duration getBindCodeTtl() {
        return bindCodeTtl;
    }

    public void setBindCodeTtl(Duration bindCodeTtl) {
        this.bindCodeTtl = bindCodeTtl;
    }

    public int getMaxActiveBindCodes() {
        return maxActiveBindCodes;
    }

    public void setMaxActiveBindCodes(int maxActiveBindCodes) {
        this.maxActiveBindCodes = Math.max(1, Math.min(maxActiveBindCodes, 10));
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public int getMaxDevicesPerUser() {
        return maxDevicesPerUser;
    }

    public void setMaxDevicesPerUser(int maxDevicesPerUser) {
        this.maxDevicesPerUser = Math.max(1, Math.min(maxDevicesPerUser, 50));
    }

    public String getMinExtensionVersion() {
        return minExtensionVersion;
    }

    /**
     * Non-empty values must be strict numeric versions (1-4 numeric segments);
     * an invalid value aborts startup binding instead of silently disabling the
     * minimum-version check. Empty still means "no minimum".
     */
    public void setMinExtensionVersion(String minExtensionVersion) {
        String value = minExtensionVersion == null ? "" : minExtensionVersion.trim();
        if (!value.isEmpty()
                && !value.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}")) {
            throw new IllegalArgumentException(
                    "PLUGIN_MIN_EXTENSION_VERSION 必须为纯数字版本格式（如 1.2.0），空值表示不启用最低版本检查"
            );
        }
        this.minExtensionVersion = value;
    }

    public int getBindIpLimit() {
        return bindIpLimit;
    }

    public void setBindIpLimit(int bindIpLimit) {
        this.bindIpLimit = Math.max(1, Math.min(bindIpLimit, 1000));
    }

    public Duration getBindIpWindow() {
        return bindIpWindow;
    }

    public void setBindIpWindow(Duration bindIpWindow) {
        this.bindIpWindow = bindIpWindow;
    }

    public int getBindCodeAttemptLimit() {
        return bindCodeAttemptLimit;
    }

    public void setBindCodeAttemptLimit(int bindCodeAttemptLimit) {
        this.bindCodeAttemptLimit = Math.max(1, Math.min(bindCodeAttemptLimit, 20));
    }

    public int getLastSeenUpdateIntervalSeconds() {
        return lastSeenUpdateIntervalSeconds;
    }

    public void setLastSeenUpdateIntervalSeconds(int lastSeenUpdateIntervalSeconds) {
        this.lastSeenUpdateIntervalSeconds = Math.max(5, Math.min(lastSeenUpdateIntervalSeconds, 3600));
    }

    public List<String> getAllowedExtensionOrigins() {
        return allowedExtensionOrigins;
    }

    /**
     * Non-empty entries must be strict {@code chrome-extension://[a-p]{32}}
     * origins; wildcards and URL patterns are rejected outright. Blank entries
     * are filtered out; an empty list means no extension origin is allowed.
     * An invalid value aborts startup binding instead of silently widening the
     * allowlist or disabling the plugin API.
     */
    public void setAllowedExtensionOrigins(List<String> allowedExtensionOrigins) {
        List<String> normalized = new ArrayList<>();
        if (allowedExtensionOrigins != null) {
            for (String raw : allowedExtensionOrigins) {
                String value = raw == null ? "" : raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (!value.matches("chrome-extension://[a-p]{32}")) {
                    throw new IllegalArgumentException(
                            "PLUGIN_ALLOWED_EXTENSION_ORIGINS 只允许精确的 chrome-extension://<32位a-p扩展ID>，"
                                    + "不支持通配符或模式"
                    );
                }
                normalized.add(value);
            }
        }
        this.allowedExtensionOrigins = List.copyOf(normalized);
    }
}
