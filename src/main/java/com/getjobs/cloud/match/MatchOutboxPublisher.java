package com.getjobs.cloud.match;

import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.match.MatchWorkerRepository.OutboxJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Publishes pending outbox events to Redis Stream from the API profile.
 * Claims PENDING entries, XADDs to the stream, and confirms PUBLISHED.
 * On failure, releases the lease with bounded exponential backoff derived
 * from the outbox attempt number so the event can be retried.
 */
@Component
@Profile("api")
public class MatchOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(MatchOutboxPublisher.class);

    private final MatchWorkerRepository outboxRepo;
    private final StringRedisTemplate redis;
    private final String streamKey;
    private final AiMatchProperties properties;

    public MatchOutboxPublisher(
            MatchWorkerRepository outboxRepo,
            StringRedisTemplate redis,
            AiMatchProperties properties
    ) {
        this.outboxRepo = outboxRepo;
        this.redis = redis;
        this.properties = properties;
        this.streamKey = defaultIfBlank(properties.getStreamKey(), "ai-jobpilot:job-match");
    }

    @Scheduled(fixedDelayString = "${app.ai-match.outbox-poll-delay:10s}")
    public void publishPendingOutbox() {
        outboxRepo.claimOutbox(properties.getLeaseSeconds()).ifPresent(this::publishToRedis);
    }

    private void publishToRedis(OutboxJob job) {
        try {
            Map<String, String> message = Map.of(
                    "matchId", job.matchId().toString(),
                    "userId", job.ownerUserId().toString(),
                    "timestamp", Instant.now().toString(),
                    "source", "outbox"
            );
            // Strict order: XADD first, then confirm PUBLISHED.
            redis.opsForStream().add(streamKey, message);
            outboxRepo.confirmOutbox(job.outboxId(), job.leaseToken());
            log.debug("Outbox 事件已发布并确认: matchId={}", job.matchId());
        } catch (RuntimeException exception) {
            log.warn("Outbox 发布失败，将按退避重试: matchId={}, 异常类型={}",
                    job.matchId(), exception.getClass().getSimpleName());
            outboxRepo.releaseOutboxLease(job.outboxId(), job.leaseToken(),
                    properties.retryDelayForAttempt(job.attemptNumber()));
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
