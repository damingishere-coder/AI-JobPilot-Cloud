package com.getjobs.cloud.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class S3FileStorageTest {
    @Test
    void writesToConfiguredPrivateBucketWithGeneratedKey() throws Exception {
        S3Client client = mock(S3Client.class);
        S3FileStorage storage = new S3FileStorage(client, "private-resumes");

        StoredObject stored = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/pdf");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("private-resumes");
        assertThat(request.getValue().key()).isEqualTo(stored.storageKey()).startsWith("objects/");
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    void rejectsMissingBucketAndUnsafeKey() {
        S3Client client = mock(S3Client.class);
        assertThatThrownBy(() -> new S3FileStorage(client, " "))
                .isInstanceOf(IllegalArgumentException.class);

        S3FileStorage storage = new S3FileStorage(client, "private-resumes");
        assertThatThrownBy(() -> storage.exists("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
