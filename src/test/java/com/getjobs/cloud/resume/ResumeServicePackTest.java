package com.getjobs.cloud.resume;

import com.getjobs.cloud.crypto.DataEncryptionService.EncryptedData;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The packed stored blob must be built from individually bounds-checked arrays
 * inside a fixed-capacity buffer: no attacker-influenced length arithmetic may
 * ever reach an allocation, and failures surface as a stable, non-sensitive
 * server error.
 */
class ResumeServicePackTest {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_PACKED = ResumeFileValidator.MAX_BYTES + NONCE_BYTES + TAG_BYTES;

    @Test
    void packsNonceBeforeCiphertextWithinTheFixedCap() {
        byte[] nonce = new byte[NONCE_BYTES];
        Arrays.fill(nonce, (byte) 0x7a);
        byte[] ciphertext = "resume-ciphertext".getBytes(StandardCharsets.UTF_8);

        byte[] packed = ResumeService.pack(new EncryptedData(ciphertext, nonce, "test-key"));

        assertThat(packed).hasSize(NONCE_BYTES + ciphertext.length);
        assertThat(Arrays.copyOfRange(packed, 0, NONCE_BYTES)).isEqualTo(nonce);
        assertThat(Arrays.copyOfRange(packed, NONCE_BYTES, packed.length)).isEqualTo(ciphertext);
    }

    @Test
    void acceptsCiphertextAtTheExactPlaintextCapPlusTagOverhead() {
        byte[] ciphertext = new byte[ResumeFileValidator.MAX_BYTES + TAG_BYTES];

        byte[] packed = ResumeService.pack(new EncryptedData(ciphertext, new byte[NONCE_BYTES], "test-key"));

        assertThat(packed).hasSize(MAX_PACKED);
    }

    @Test
    void rejectsMalformedNonceLengths() {
        EncryptedData tooShort = new EncryptedData(new byte[]{1, 2}, new byte[NONCE_BYTES - 1], "test-key");
        EncryptedData tooLong = new EncryptedData(new byte[]{1, 2}, new byte[NONCE_BYTES + 1], "test-key");
        EncryptedData missing = new EncryptedData(new byte[]{1, 2}, null, "test-key");

        assertThatThrownBy(() -> ResumeService.pack(tooShort))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
        assertThatThrownBy(() -> ResumeService.pack(tooLong))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
        assertThatThrownBy(() -> ResumeService.pack(missing))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
    }

    @Test
    void rejectsEmptyAndOversizedCiphertextWithoutAttackerSizedAllocation() {
        EncryptedData empty = new EncryptedData(new byte[0], new byte[NONCE_BYTES], "test-key");
        EncryptedData missing = new EncryptedData(null, new byte[NONCE_BYTES], "test-key");
        // One byte past the plaintext cap + fixed GCM tag: rejected before any
        // buffer sized by the ciphertext length is allocated.
        EncryptedData oversized = new EncryptedData(
                new byte[ResumeFileValidator.MAX_BYTES + TAG_BYTES + 1], new byte[NONCE_BYTES], "test-key"
        );

        assertThatThrownBy(() -> ResumeService.pack(empty))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
        assertThatThrownBy(() -> ResumeService.pack(missing))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
        assertThatThrownBy(() -> ResumeService.pack(oversized))
                .isInstanceOfSatisfying(ApiException.class, ResumeServicePackTest::assertStableServerError);
    }

    private static void assertStableServerError(ApiException exception) {
        assertThat(exception.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exception.code()).isEqualTo("INTERNAL_ERROR");
        // The message is a fixed server-side string; it never echoes lengths or
        // ciphertext/nonce details.
        assertThat(exception.getMessage()).isEqualTo("服务暂时无法处理请求");
    }
}
