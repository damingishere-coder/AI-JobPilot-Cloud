package com.getjobs.cloud.crypto;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
@Profile({"api", "worker"})
public class DataEncryptionService {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public DataEncryptionService(DataEncryptionProperties properties) {
        this.key = resolveKey(properties);
        if (properties.getKeyId() == null || properties.getKeyId().isBlank()) {
            throw new IllegalStateException("数据加密 Key ID 不能为空");
        }
        this.keyId = properties.getKeyId().trim();
    }

    public EncryptedData encrypt(byte[] plaintext, String associatedData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new EncryptedData(cipher.doFinal(plaintext), nonce, keyId);
        } catch (Exception exception) {
            throw new IllegalStateException("敏感数据加密失败", exception);
        }
    }

    public byte[] decrypt(EncryptedData encrypted, String associatedData) {
        if (!keyId.equals(encrypted.keyId())) {
            throw new IllegalStateException("无法识别数据加密 Key ID");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (Exception exception) {
            throw new IllegalStateException("敏感数据解密失败", exception);
        }
    }

    public String keyId() {
        return keyId;
    }

    private SecretKey resolveKey(DataEncryptionProperties properties) {
        String configured = properties.getKey();
        if (configured != null && !configured.isBlank()) {
            try {
                byte[] bytes = HexFormat.of().parseHex(configured.trim());
                if (bytes.length != 32) {
                    throw new IllegalStateException("数据加密 Key 必须是 64 位十六进制字符串");
                }
                return new SecretKeySpec(bytes, "AES");
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("数据加密 Key 必须是 64 位十六进制字符串", exception);
            }
        }
        if (!properties.isAllowEphemeralKey()) {
            throw new IllegalStateException("缺少数据加密 Key，Cloud API/Worker 拒绝启动");
        }
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, secureRandom);
            return generator.generateKey();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成测试用临时加密 Key", exception);
        }
    }

    public record EncryptedData(byte[] ciphertext, byte[] nonce, String keyId) {
    }
}
