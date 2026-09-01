package com.getjobs.cloud.auth;

import com.getjobs.cloud.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;

@Component
@Profile("api")
public class AccountDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionWorker.class);

    private final AccountDeletionTransactions transactions;
    private final AccountDeletionProperties properties;
    private final FileStorage storage;
    private final Clock clock;

    public AccountDeletionWorker(
            AccountDeletionTransactions transactions,
            AccountDeletionProperties properties,
            FileStorage storage,
            Clock clock
    ) {
        this.transactions = transactions;
        this.properties = properties;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.account-deletion.poll-delay:60s}")
    public void poll() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        processOne();
    }

    public boolean processOne() {
        var claimed = transactions.claim(properties.getLeaseSeconds());
        if (claimed.isEmpty()) {
            return false;
        }
        AccountDeletionRepository.ClaimedDeletion deletion = claimed.get();
        try {
            var keys = transactions.storageKeys(deletion.userId());
            for (String key : keys) {
                storage.delete(key);
            }
            if (!transactions.complete(
                    deletion.requestId(),
                    clock.instant().plus(properties.getBackupRetention())
            )) {
                throw new IllegalStateException("删除任务状态已变化");
            }
            return true;
        } catch (IOException exception) {
            transactions.retry(deletion.requestId(), "STORAGE_DELETE_FAILED", properties.getMaxAttempts());
            log.warn("账号删除任务稍后重试，请求={}，错误=STORAGE_DELETE_FAILED", deletion.requestId());
            return false;
        } catch (RuntimeException exception) {
            transactions.retry(deletion.requestId(), "PURGE_FAILED", properties.getMaxAttempts());
            log.warn("账号删除任务稍后重试，请求={}，错误类型={}", deletion.requestId(), exception.getClass().getSimpleName());
            return false;
        }
    }
}
