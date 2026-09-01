package com.getjobs.cloud.auth;

import com.getjobs.cloud.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDeletionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void disabledWorkerDoesNotClaimAnything() {
        AccountDeletionTransactions transactions = mock(AccountDeletionTransactions.class);
        AccountDeletionProperties properties = new AccountDeletionProperties();
        properties.setWorkerEnabled(false);
        new AccountDeletionWorker(transactions, properties, mock(FileStorage.class), fixedClock()).poll();
        verify(transactions, never()).claim(properties.getLeaseSeconds());
    }

    @Test
    void deletesStorageThenPurgesDatabaseWith180DayTombstone() throws Exception {
        AccountDeletionTransactions transactions = mock(AccountDeletionTransactions.class);
        FileStorage storage = mock(FileStorage.class);
        AccountDeletionProperties properties = new AccountDeletionProperties();
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(transactions.claim(properties.getLeaseSeconds())).thenReturn(Optional.of(
                new AccountDeletionRepository.ClaimedDeletion(requestId, userId)
        ));
        when(transactions.storageKeys(userId)).thenReturn(List.of("objects/a", "avatars/b"));
        when(transactions.complete(requestId, NOW.plus(properties.getBackupRetention()))).thenReturn(true);

        assertThat(new AccountDeletionWorker(transactions, properties, storage, fixedClock()).processOne()).isTrue();

        verify(storage).delete("objects/a");
        verify(storage).delete("avatars/b");
        verify(transactions).complete(requestId, Instant.parse("2027-02-28T00:00:00Z"));
        verify(transactions, never()).retry(requestId, "PURGE_FAILED", properties.getMaxAttempts());
    }

    @Test
    void storageFailureKeepsRequestForRetryWithoutPurgingDatabase() throws Exception {
        AccountDeletionTransactions transactions = mock(AccountDeletionTransactions.class);
        FileStorage storage = mock(FileStorage.class);
        AccountDeletionProperties properties = new AccountDeletionProperties();
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(transactions.claim(properties.getLeaseSeconds())).thenReturn(Optional.of(
                new AccountDeletionRepository.ClaimedDeletion(requestId, userId)
        ));
        when(transactions.storageKeys(userId)).thenReturn(List.of("objects/a"));
        doThrow(new IOException("synthetic")).when(storage).delete("objects/a");

        assertThat(new AccountDeletionWorker(transactions, properties, storage, fixedClock()).processOne()).isFalse();

        verify(transactions).retry(requestId, "STORAGE_DELETE_FAILED", properties.getMaxAttempts());
        verify(transactions, never()).complete(requestId, NOW.plus(properties.getBackupRetention()));
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
