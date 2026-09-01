package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@Profile("api")
public class AccountDeletionService {
    private final UserRepository users;
    private final AccountDeletionRepository deletions;
    private final PasswordEncoder passwordEncoder;
    private final SecurityFingerprintService fingerprints;
    private final AuditLogService auditLogs;

    public AccountDeletionService(
            UserRepository users,
            AccountDeletionRepository deletions,
            PasswordEncoder passwordEncoder,
            SecurityFingerprintService fingerprints,
            AuditLogService auditLogs
    ) {
        this.users = users;
        this.deletions = deletions;
        this.passwordEncoder = passwordEncoder;
        this.fingerprints = fingerprints;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public AccountDeletionRepository.DeletionRequest request(
            UUID userId,
            String password,
            String confirmation,
            String idempotencyKey,
            RequestMetadata metadata
    ) {
        if (!"永久删除".equals(confirmation)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONFIRMATION_INVALID", "请输入“永久删除”进行确认");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key 必须为 1–128 个字符");
        }
        String idempotencyHash = fingerprints.hash(userId + ":" + idempotencyKey);
        var existing = deletions.find(userId, idempotencyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        UserAccount account = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录"));
        if (account.status() != UserStatus.ACTIVE || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REAUTHENTICATION_FAILED", "当前密码不正确");
        }

        AccountDeletionRepository.DeletionRequest deletion = deletions.create(
                userId,
                UUID.randomUUID(),
                idempotencyHash
        );
        auditLogs.append(
                userId,
                account.role(),
                "AUTH_ACCOUNT_DELETION_REQUESTED",
                "SUCCESS",
                metadata,
                Map.of("deletionRequestId", deletion.id().toString())
        );
        return deletion;
    }
}
