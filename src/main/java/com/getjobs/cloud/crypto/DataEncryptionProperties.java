package com.getjobs.cloud.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.data-encryption")
public class DataEncryptionProperties {
    private String key = "";
    private String keyId = "v1";
    private boolean allowEphemeralKey;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public boolean isAllowEphemeralKey() {
        return allowEphemeralKey;
    }

    public void setAllowEphemeralKey(boolean allowEphemeralKey) {
        this.allowEphemeralKey = allowEphemeralKey;
    }
}
