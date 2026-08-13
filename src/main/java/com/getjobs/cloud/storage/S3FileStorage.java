package com.getjobs.cloud.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

public class S3FileStorage implements FileStorage {
    private final S3Client client;
    private final String bucket;

    public S3FileStorage(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "s3 client");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 bucket 不能为空");
        }
        this.bucket = bucket;
    }

    @Override
    public StoredObject store(InputStream input, long contentLength, String contentType) throws IOException {
        Objects.requireNonNull(input, "input");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength 不能为负数");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String storageKey = "objects/%04d/%02d/%s".formatted(
                today.getYear(), today.getMonthValue(), UUID.randomUUID()
        );
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(contentType == null || contentType.isBlank()
                        ? "application/octet-stream"
                        : contentType)
                .build();
        try {
            client.putObject(request, RequestBody.fromInputStream(input, contentLength));
            return new StoredObject(storageKey, contentLength, request.contentType());
        } catch (RuntimeException e) {
            throw new IOException("S3 对象写入失败", e);
        }
    }

    @Override
    public InputStream read(String storageKey) throws IOException {
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(validateKey(storageKey)).build()
            );
            return response;
        } catch (RuntimeException e) {
            throw new IOException("S3 对象读取失败", e);
        }
    }

    @Override
    public boolean exists(String storageKey) throws IOException {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(validateKey(storageKey)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new IOException("S3 对象状态检查失败", e);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(validateKey(storageKey))
                    .build());
        } catch (RuntimeException e) {
            throw new IOException("S3 对象删除失败", e);
        }
    }

    @Override
    public void checkHealth() throws IOException {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException e) {
            throw new IOException("S3 bucket 不可用", e);
        }
    }

    private String validateKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.startsWith("/")
                || storageKey.contains("..")
                || storageKey.contains("\\")) {
            throw new IllegalArgumentException("非法 storageKey");
        }
        return storageKey;
    }
}
