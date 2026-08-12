package com.getjobs.cloud.match;

import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.match.MatchWorkerRepository.OutboxJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MatchOutboxPublisherTest {

    @Mock private MatchWorkerRepository outboxRepo;
    @Mock private StringRedisTemplate redis;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    private AiMatchProperties properties;
    private MatchOutboxPublisher publisher;

    private final UUID outboxId = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID leaseToken = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new AiMatchProperties();
        properties.setApiKey("test-key");
        properties.setLeaseSeconds(900);
        properties.setRetryBaseDelaySeconds(5);
        properties.setRetryMaxDelaySeconds(300);

        when(redis.opsForStream()).thenReturn(streamOps);

        publisher = new MatchOutboxPublisher(outboxRepo, redis, properties);
    }

    private OutboxJob jobWithAttempt(int attemptNumber) {
        return new OutboxJob(outboxId, userId, matchId, "JOB_ANALYSIS_REQUESTED", leaseToken, attemptNumber);
    }

    @Test
    void publishesToRedisAndConfirmsInOrderOnSuccess() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(1)));
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(RecordId.of("test-record-id"));
        when(outboxRepo.confirmOutbox(eq(outboxId), eq(leaseToken)))
                .thenReturn(true);

        publisher.publishPendingOutbox();

        // XADD must happen before confirm
        InOrder order = inOrder(streamOps, outboxRepo);
        order.verify(streamOps).add(eq("ai-jobpilot:job-match"), argThat((Map<String, String> map) ->
                map.containsKey("matchId") && map.containsKey("userId") && map.containsKey("source")));
        order.verify(outboxRepo).confirmOutbox(outboxId, leaseToken);
        verify(outboxRepo, never()).releaseOutboxLease(any(), any(), anyInt());
    }

    @Test
    void claimsWithConfiguredLeaseSeconds() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(1)));
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(RecordId.of("test-record-id"));
        when(outboxRepo.confirmOutbox(any(), any())).thenReturn(true);

        publisher.publishPendingOutbox();

        verify(outboxRepo).claimOutbox(900);
    }

    @Test
    void releasesLeaseOnRedisAddFailureWithBaseDelayForAttemptOne() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(1)));
        when(streamOps.add(anyString(), anyMap()))
                .thenThrow(new RuntimeException("Redis unavailable"));
        when(outboxRepo.releaseOutboxLease(eq(outboxId), eq(leaseToken), anyInt()))
                .thenReturn(true);

        publisher.publishPendingOutbox();

        // attempt 1 → base delay 5s
        verify(outboxRepo).releaseOutboxLease(eq(outboxId), eq(leaseToken), eq(5));
        verify(outboxRepo, never()).confirmOutbox(any(), any());
    }

    @Test
    void releasesLeaseOnRedisAddFailureWithDoubledDelayForAttemptTwo() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(2)));
        when(streamOps.add(anyString(), anyMap()))
                .thenThrow(new RuntimeException("Redis unavailable"));
        when(outboxRepo.releaseOutboxLease(eq(outboxId), eq(leaseToken), anyInt()))
                .thenReturn(true);

        publisher.publishPendingOutbox();

        // attempt 2 → base * 2 = 10s
        verify(outboxRepo).releaseOutboxLease(eq(outboxId), eq(leaseToken), eq(10));
        verify(outboxRepo, never()).confirmOutbox(any(), any());
    }

    @Test
    void releasesLeaseWithDelayCappedAtMaxForHighAttempts() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(7)));
        when(streamOps.add(anyString(), anyMap()))
                .thenThrow(new RuntimeException("Redis unavailable"));
        when(outboxRepo.releaseOutboxLease(eq(outboxId), eq(leaseToken), anyInt()))
                .thenReturn(true);

        publisher.publishPendingOutbox();

        // attempt 7 → base * 2^6 = 320 capped at max 300
        verify(outboxRepo).releaseOutboxLease(eq(outboxId), eq(leaseToken), eq(300));
        verify(outboxRepo, never()).confirmOutbox(any(), any());
    }

    @Test
    void doesNotReleaseWhenConfirmReturnsFalse() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(1)));
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(RecordId.of("test-record-id"));
        when(outboxRepo.confirmOutbox(eq(outboxId), eq(leaseToken)))
                .thenReturn(false);

        // confirm returns false means the lease was already invalid — nothing to release
        publisher.publishPendingOutbox();

        verify(outboxRepo).confirmOutbox(outboxId, leaseToken);
        verify(outboxRepo, never()).releaseOutboxLease(any(), any(), anyInt());
    }

    @Test
    void skipsWhenNoOutboxEntryToClaim() {
        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.empty());

        publisher.publishPendingOutbox();

        verify(streamOps, never()).add(anyString(), anyMap());
        verify(outboxRepo, never()).confirmOutbox(any(), any());
        verify(outboxRepo, never()).releaseOutboxLease(any(), any(), anyInt());
    }

    @Test
    void usesCustomStreamKeyFromProperties() {
        properties.setStreamKey("custom:stream:key");
        publisher = new MatchOutboxPublisher(outboxRepo, redis, properties);

        when(outboxRepo.claimOutbox(anyInt())).thenReturn(Optional.of(jobWithAttempt(1)));
        when(streamOps.add(anyString(), anyMap()))
                .thenReturn(RecordId.of("test-record-id"));
        when(outboxRepo.confirmOutbox(any(), any())).thenReturn(true);

        publisher.publishPendingOutbox();

        verify(streamOps).add(eq("custom:stream:key"), anyMap());
    }
}
