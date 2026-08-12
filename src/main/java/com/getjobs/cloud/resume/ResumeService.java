package com.getjobs.cloud.resume;

import com.getjobs.cloud.crypto.DataEncryptionService;
import com.getjobs.cloud.malware.MalwareScanner;
import com.getjobs.cloud.malware.MalwareScannerUnavailableException;
import com.getjobs.cloud.storage.FileStorage;
import com.getjobs.cloud.storage.StoredObject;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.PageResult;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Profile("api")
public class ResumeService {
    private final ResumeRepository resumes;
    private final ResumeFileValidator validator;
    private final MalwareScanner malwareScanner;
    private final DataEncryptionService encryption;
    private final FileStorage storage;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ResumeService(
            ResumeRepository resumes,
            ResumeFileValidator validator,
            MalwareScanner malwareScanner,
            DataEncryptionService encryption,
            FileStorage storage,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.resumes = resumes;
        this.validator = validator;
        this.malwareScanner = malwareScanner;
        this.encryption = encryption;
        this.storage = storage;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public UploadOutcome upload(UUID userId, MultipartFile file, boolean setCurrent, String idempotencyKey) {
        String uploadKeyHash = hashIdempotencyKey(idempotencyKey);
        byte[] content = readBounded(file);
        MalwareScanner.ScanResult scan;
        try {
            scan = malwareScanner.scan(content);
        } catch (MalwareScannerUnavailableException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DEPENDENCY_UNAVAILABLE",
                    "简历安全扫描服务暂时不可用，请稍后重试",
                    true,
                    30,
                    java.util.List.of()
            );
        }
        if (!scan.clean()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MALWARE_SUSPECTED",
                    "文件未通过安全检查，请更换可信文件"
            );
        }
        ResumeFileValidator.ValidatedFile validated = validator.validate(
                content,
                file.getOriginalFilename(),
                file.getContentType()
        );

        String sha256 = sha256(content);
        Optional<ResumeRecord> existing = inTenant(userId, () -> resumes.findByUploadKey(userId, uploadKeyHash));
        if (existing.isPresent()) {
            return existingOutcome(existing.get(), sha256);
        }

        UUID resumeId = UUID.randomUUID();
        DataEncryptionService.EncryptedData encrypted = encryption.encrypt(content, fileAad(resumeId));
        byte[] storedPayload = pack(encrypted);
        StoredObject stored;
        try {
            stored = storage.store(
                    new ByteArrayInputStream(storedPayload),
                    storedPayload.length,
                    "application/octet-stream"
            );
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STORAGE_UNAVAILABLE",
                    "简历存储暂时不可用，请稍后重试",
                    true,
                    30,
                    java.util.List.of()
            );
        }

        try {
            ResumeRecord created = inTenant(userId, () -> {
                resumes.lockUser(userId);
                if (setCurrent) {
                    resumes.clearCurrent(userId);
                }
                return resumes.insert(
                        resumeId,
                        userId,
                        validated.filename(),
                        stored.storageKey(),
                        validated.contentType(),
                        content.length,
                        sha256,
                        uploadKeyHash,
                        encrypted.keyId(),
                        setCurrent
                );
            });
            return new UploadOutcome(toView(created, null), false);
        } catch (DuplicateKeyException exception) {
            safeDelete(stored.storageKey());
            ResumeRecord raced = inTenant(userId, () -> resumes.findByUploadKey(userId, uploadKeyHash))
                    .orElseThrow(() -> exception);
            return existingOutcome(raced, sha256);
        } catch (RuntimeException exception) {
            safeDelete(stored.storageKey());
            throw exception;
        }
    }

    public PageResult<ResumeApiModels.ResumeView> list(UUID userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        return inTenant(userId, () -> {
            long total = resumes.count(userId);
            var items = resumes.list(userId, safeSize, (safePage - 1) * safeSize).stream()
                    .map(record -> toView(record, null))
                    .toList();
            return PageResult.of(items, safePage, safeSize, total);
        });
    }

    public ResumeApiModels.ResumeView current(UUID userId, boolean includeExtractedText) {
        ResumeRecord record = inTenant(userId, () -> resumes.findCurrent(userId)).orElse(null);
        if (record == null) {
            return null;
        }
        String text = null;
        if (includeExtractedText
                && record.extractedTextCiphertext() != null
                && record.extractedTextNonce() != null) {
            byte[] plaintext = encryption.decrypt(
                    new DataEncryptionService.EncryptedData(
                            record.extractedTextCiphertext(),
                            record.extractedTextNonce(),
                            record.encryptionKeyId()
                    ),
                    textAad(record.id(), record.textVersion())
            );
            text = new String(plaintext, StandardCharsets.UTF_8);
        }
        return toView(record, text);
    }

    public ResumeApiModels.DeletePayload delete(UUID userId, UUID resumeId, int expectedVersion) {
        return inTenant(userId, () -> {
            ResumeRecord record = resumes.findByIdForUpdate(userId, resumeId)
                    .orElseThrow(() -> notFound());
            if (record.deletedAt() != null) {
                return new ResumeApiModels.DeletePayload(
                        record.id(), record.purgedAt() == null ? "SCHEDULED" : "PURGED",
                        record.deletedAt(), record.version()
                );
            }
            if (record.version() != expectedVersion) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "RESOURCE_VERSION_CONFLICT",
                        "简历已发生变化，请刷新后重试"
                );
            }
            ResumeRecord deleted = resumes.markDeleted(userId, resumeId, clock.instant());
            return new ResumeApiModels.DeletePayload(
                    deleted.id(), "SCHEDULED", deleted.deletedAt(), deleted.version()
            );
        });
    }

    private UploadOutcome existingOutcome(ResumeRecord existing, String sha256) {
        if (!existing.sha256().equals(sha256)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "同一幂等键已用于其他上传请求"
            );
        }
        if (existing.deletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "该幂等键对应的上传已删除，请使用新的幂等键重新上传"
            );
        }
        return new UploadOutcome(toView(existing, null), true);
    }

    private <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private byte[] readBounded(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请选择简历文件");
        }
        if (file.getSize() > ResumeFileValidator.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "简历文件不能超过 10 MiB");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] content = input.readNBytes(ResumeFileValidator.MAX_BYTES + 1);
            if (content.length > ResumeFileValidator.MAX_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "简历文件不能超过 10 MiB");
            }
            return content;
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", "无法读取上传文件");
        }
    }

    private String hashIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key 不能为空且不能超过 128 个字符");
        }
        return sha256(key.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算文件摘要", exception);
        }
    }

    private byte[] pack(DataEncryptionService.EncryptedData encrypted) {
        return ByteBuffer.allocate(encrypted.nonce().length + encrypted.ciphertext().length)
                .put(encrypted.nonce())
                .put(encrypted.ciphertext())
                .array();
    }

    private void safeDelete(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException ignored) {
            // The database transaction already failed. The orphan is not externally addressable
            // and can be found by storage reconciliation without hiding the original error.
        }
    }

    private ResumeApiModels.ResumeView toView(ResumeRecord record, String extractedText) {
        return new ResumeApiModels.ResumeView(
                record.id(), record.originalFilename(), record.contentType(), record.fileSize(),
                record.parseStatus(), record.parseMessage(), record.current(), record.version(),
                record.createdAt(), record.updatedAt(), record.parsedAt(), extractedText
        );
    }

    static String fileAad(UUID resumeId) {
        return "resume-file:" + resumeId;
    }

    static String textAad(UUID resumeId, int textVersion) {
        return "resume-text:" + resumeId + ":" + textVersion;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "简历不存在");
    }

    public record UploadOutcome(ResumeApiModels.ResumeView resume, boolean deduplicated) {
    }
}
