package com.getjobs.cloud.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("api")
public class AccountDeletionTransactions {
    private final AccountDeletionRepository deletions;
    private final UserTransactionExecutor userTransactions;

    public AccountDeletionTransactions(
            AccountDeletionRepository deletions,
            UserTransactionExecutor userTransactions
    ) {
        this.deletions = deletions;
        this.userTransactions = userTransactions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AccountDeletionRepository.ClaimedDeletion> claim(int leaseSeconds) {
        return deletions.claim(leaseSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String> storageKeys(UUID userId) {
        return userTransactions.execute(userId, () -> deletions.storageKeys(userId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID requestId, Instant backupExpiresAt) {
        return deletions.complete(requestId, backupExpiresAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(UUID requestId, String errorCode, int maxAttempts) {
        deletions.retry(requestId, errorCode, maxAttempts);
    }
}
