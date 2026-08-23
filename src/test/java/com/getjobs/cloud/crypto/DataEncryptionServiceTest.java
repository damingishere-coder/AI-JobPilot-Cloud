package com.getjobs.cloud.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataEncryptionServiceTest {
    @Test
    void encryptsWithRandomNonceAndRequiresMatchingAssociatedData() {
        DataEncryptionProperties properties = new DataEncryptionProperties();
        properties.setAllowEphemeralKey(true);
        DataEncryptionService service = new DataEncryptionService(properties);
        byte[] plaintext = "仅用于测试的简历文本".getBytes(StandardCharsets.UTF_8);

        DataEncryptionService.EncryptedData first = service.encrypt(plaintext, "resume:one");
        DataEncryptionService.EncryptedData second = service.encrypt(plaintext, "resume:one");

        assertThat(first.ciphertext()).isNotEqualTo(plaintext);
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(service.decrypt(first, "resume:one")).isEqualTo(plaintext);
        assertThatThrownBy(() -> service.decrypt(first, "resume:two"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("敏感数据解密失败");
    }

    @Test
    void refusesToStartWithoutProductionKey() {
        DataEncryptionProperties properties = new DataEncryptionProperties();

        assertThatThrownBy(() -> new DataEncryptionService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少数据加密 Key");
    }
}
