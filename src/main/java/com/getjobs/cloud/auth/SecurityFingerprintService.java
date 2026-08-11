package com.getjobs.cloud.auth;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
@Profile("api")
public class SecurityFingerprintService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public SecurityFingerprintService(AuthProperties properties) {
        this.key = new SecretKeySpec(properties.getHashPepper().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成安全审计哈希", exception);
        }
    }
}
