package com.getjobs.cloud.resume;

import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.crypto.DataEncryptionService;
import com.getjobs.cloud.storage.FileStorage;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

@Component
@Profile("worker")
@Slf4j
public class ResumeWorker {
    private static final int LEASE_SECONDS = 300;
    private static final int MAX_ENCRYPTED_FILE_BYTES = ResumeFileValidator.MAX_BYTES + 128;

    private final ResumeWorkerRepository resumes;
    private final ResumeTextExtractor extractor;
    private final DataEncryptionService encryption;
    private final FileStorage storage;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final AuditWriter audit;

    public ResumeWorker(
            ResumeWorkerRepository resumes,
            ResumeTextExtractor extractor,
            DataEncryptionService encryption,
            FileStorage storage,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            AuditWriter audit
    ) {
        this.resumes = resumes;
        this.extractor = extractor;
        this.encryption = encryption;
        this.storage = storage;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.resume.worker-poll-delay:2s}")
    public void processNext() {
        resumes.claimParse(LEASE_SECONDS).ifPresent(this::parse);
        resumes.claimPurge(LEASE_SECONDS).ifPresent(this::purge);
    }

    private void parse(ResumeWorkerRepository.ParseJob job) {
        try {
            byte[] payload = readEncrypted(job.storageKey());
            if (payload.length <= 12) {
                throw new ResumeParseException("简历加密对象损坏，请重新上传");
            }
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(payload, 12, payload.length);
            byte[] plaintext = encryption.decrypt(
                    new DataEncryptionService.EncryptedData(ciphertext, nonce, job.encryptionKeyId()),
                    ResumeService.fileAad(job.resumeId())
            );
            String text = extractor.extract(plaintext, job.contentType());
            DataEncryptionService.EncryptedData encryptedText = encryption.encrypt(
                    text.getBytes(StandardCharsets.UTF_8),
                    ResumeService.textAad(job.resumeId(), job.textVersion())
            );
            boolean saved = inTenant(job.userId(), () -> resumes.markParsed(
                    job,
                    encryptedText.ciphertext(),
                    encryptedText.nonce(),
                    encryptedText.keyId(),
                    Instant.now()
            ));
            if (saved) {
                audit.append(
                        job.userId(), "SYSTEM", null, "RESUME_PARSE_SUCCEEDED", "RESUME",
                        job.resumeId(), "SUCCESS", null, null, "Worker",
                        Map.of("textVersion", job.textVersion())
                );
            }
        } catch (ResumeParseException | IllegalStateException exception) {
            fail(job, exception.getMessage());
        } catch (IOException exception) {
            retryOrFail(job);
        } catch (RuntimeException exception) {
            log.error("简历解析出现未预期错误，resumeId={}，类型={}", job.resumeId(), exception.getClass().getSimpleName());
            retryOrFail(job);
        }
    }

    private void purge(ResumeWorkerRepository.PurgeJob job) {
        try {
            storage.delete(job.storageKey());
            boolean purged = inTenant(job.userId(), () -> resumes.markPurged(job, Instant.now()));
            if (purged) {
                audit.append(
                        job.userId(), "SYSTEM", null, "RESUME_PURGED", "RESUME",
                        job.resumeId(), "SUCCESS", null, null, "Worker", Map.of()
                );
            }
        } catch (IOException exception) {
            log.warn("简历文件清理暂时失败，将等待租约后重试，resumeId={}", job.resumeId());
        }
    }

    private byte[] readEncrypted(String storageKey) throws IOException {
        try (InputStream input = storage.read(storageKey)) {
            byte[] payload = input.readNBytes(MAX_ENCRYPTED_FILE_BYTES + 1);
            if (payload.length > MAX_ENCRYPTED_FILE_BYTES) {
                throw new ResumeParseException("简历加密对象大小异常，请重新上传");
            }
            return payload;
        }
    }

    private void retryOrFail(ResumeWorkerRepository.ParseJob job) {
        if (job.attemptNumber() >= 3) {
            fail(job, "解析服务多次失败，请重新上传简历");
        } else {
            inTenant(job.userId(), () -> resumes.reschedule(job));
        }
    }

    private void fail(ResumeWorkerRepository.ParseJob job, String message) {
        boolean failed = inTenant(job.userId(), () -> resumes.markFailed(job, message));
        if (failed) {
            audit.append(
                    job.userId(), "SYSTEM", null, "RESUME_PARSE_FAILED", "RESUME",
                    job.resumeId(), "FAILED", null, null, "Worker",
                    Map.of("reason", "TEXT_EXTRACTION_FAILED")
            );
        }
    }

    private <T> T inTenant(java.util.UUID userId, java.util.function.Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }
}
