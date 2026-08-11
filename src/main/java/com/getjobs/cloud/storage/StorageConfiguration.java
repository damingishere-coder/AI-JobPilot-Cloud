package com.getjobs.cloud.storage;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    FileStorage localFileStorage(StorageProperties properties) {
        return new LocalPrivateFileStorage(properties.getLocalRoot());
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    S3Client s3Client(StorageProperties properties) {
        StorageProperties.S3 settings = properties.getS3();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(settings.getRegion()))
                .forcePathStyle(settings.isPathStyleAccess());
        if (settings.getEndpoint() != null && !settings.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(settings.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    FileStorage s3FileStorage(S3Client client, StorageProperties properties) {
        return new S3FileStorage(client, properties.getS3().getBucket());
    }

    @Bean(name = "storage")
    HealthIndicator storageHealthIndicator(FileStorage storage) {
        return () -> {
            try {
                storage.checkHealth();
                return Health.up().build();
            } catch (Exception e) {
                return Health.down().build();
            }
        };
    }
}
