package com.getjobs.cloud.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigurationTest {
    @Test
    void buildsS3CompatibleClientFromConfiguredRegionAndEndpoint() {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.Type.S3);
        properties.getS3().setBucket("private-resumes");
        properties.getS3().setRegion("ap-southeast-1");
        properties.getS3().setEndpoint("http://minio.internal:9000");
        properties.getS3().setPathStyleAccess(true);

        StorageConfiguration configuration = new StorageConfiguration();
        try (S3Client client = configuration.s3Client(properties)) {
            assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("ap-southeast-1");
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://minio.internal:9000"));
            assertThat(configuration.s3FileStorage(client, properties)).isInstanceOf(S3FileStorage.class);
        }
    }
}
