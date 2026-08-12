package com.getjobs.cloud.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.ai.AiMatchClient;
import com.getjobs.cloud.ai.AiMatchException;
import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.crypto.DataEncryptionService;
import com.getjobs.cloud.match.MatchWorkerRepository.OutboxJob;
import com.getjobs.cloud.match.MatchWorkerRepository.ProcessJob;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("worker")
@Slf4j
public class MatchWorker {

    private final MatchWorkerRepository matchRepo;
    private final DataEncryptionService encryption;
    private final AiMatchClient aiClient;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditWriter audit;

    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final AiMatchProperties properties;

    public MatchWorker(
            MatchWorkerRepository matchRepo,
            DataEncryptionService encryption,
            AiMatchClient aiClient,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            AuditWriter audit,
            AiMatchProperties properties
    ) {
        this.matchRepo = matchRepo;
        this.encryption = encryption;
        this.aiClient = aiClient;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.properties = properties;

        this.streamKey = defaultIfBlank(properties.getStreamKey(), "ai-jobpilot:job-match");
        this.consumerGroup = defaultIfBlank(properties.getConsumerGroup(), "job-match-workers");
        this.consumerName = defaultIfBlank(properties.getConsumerName(),
                "worker-" + UUID.randomUUID().toString().substring(0, 8));
        this.leaseSeconds = properties.getLeaseSeconds();
        this.maxAttempts = properties.getMaxAttempts();

        ensureConsumerGroup();
    }

    private void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(streamKey, consumerGroup);
        } catch (RuntimeException ignored) {
            // Group already exists
        }
    }

    @Scheduled(fixedDelayString = "${app.ai-match.stream-poll-delay:2s}")
    public void consumeFromRedis() {
        try {
            var messages = redis.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );
            if (messages == null || messages.isEmpty()) {
                return;
            }
            for (var message : messages) {
                processStreamMessage(message);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis Stream 消费异常，将重试，原因={}", exception.getMessage());
        }
    }

    /**
     * Outbox publisher: claims PENDING outbox entry, XADDs to Redis, then confirms.
     */
    @Scheduled(fixedDelayString = "${app.ai-match.outbox-poll-delay:10s}")
    public void outboxPublishScan() {
        matchRepo.claimOutbox(leaseSeconds).ifPresent(this::publishOutboxToRedis);
    }

    private void publishOutboxToRedis(OutboxJob job) {
        try {
            Map<String, String> message = Map.of(
                    "matchId", job.matchId().toString(),
                    "userId", job.ownerUserId().toString(),
                    "timestamp", Instant.now().toString(),
                    "source", "outbox"
            );
            redis.opsForStream().add(streamKey, message);
            matchRepo.confirmOutbox(job.outboxId(), job.leaseToken());
            log.debug("Outbox 事件已发布并确认: matchId={}", job.matchId());
        } catch (RuntimeException exception) {
            log.warn("Outbox 发布失败，将按退避重试: matchId={}, 异常类型={}",
                    job.matchId(), exception.getClass().getSimpleName());
            // attemptNumber comes from claim_match_outbox_publish; backoff grows with it
            matchRepo.releaseOutboxLease(job.outboxId(), job.leaseToken(),
                    properties.retryDelayForAttempt(job.attemptNumber()));
        }
    }

    /**
     * Low-frequency DB fallback: directly claim one PENDING/expired-PROCESSING
     * match from the database, bypassing Redis entirely. Handles cases where
     * Redis messages are lost, PEL has gaps, or the stream is unavailable.
     */
    @Scheduled(fixedDelayString = "${app.ai-match.db-fallback-delay:30s}")
    public void dbFallbackScan() {
        try {
            matchRepo.claimOnePendingMatch(leaseSeconds, maxAttempts).ifPresent(job -> {
                try {
                    boolean handled = processClaimedMatch(job);
                    if (!handled) {
                        log.warn("DB fallback 状态转换失败 matchId={}", job.matchId());
                    }
                } catch (RuntimeException exception) {
                    log.error("DB fallback 处理异常 matchId={}，类型={}",
                            job.matchId(), exception.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("DB fallback 扫描异常，类型={}", exception.getClass().getSimpleName());
        }
    }

    private void processStreamMessage(MapRecord<String, Object, Object> message) {
        Map<Object, Object> value = message.getValue();
        String matchIdStr = stringValue(value.get("matchId"));
        String userIdStr = stringValue(value.get("userId"));

        if (matchIdStr == null || userIdStr == null) {
            log.warn("Redis Stream 消息缺少必要字段，跳过并 ACK");
            ack(message);
            return;
        }

        UUID matchId;
        UUID userId;
        try {
            matchId = UUID.fromString(matchIdStr);
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException exception) {
            log.warn("Redis Stream 消息 ID 格式错误，跳过并 ACK");
            ack(message);
            return;
        }

        try {
            // Claim the match via DB within user's tenant context
            ProcessJob job = inTenant(userId, () ->
                    matchRepo.claimMatch(userId, matchId, leaseSeconds, maxAttempts).orElse(null)
            );

            if (job == null) {
                // Already claimed, in terminal state, not due yet, or attempts exhausted
                // → the DB claim rejected it, safe to ACK.
                ack(message);
                return;
            }

            boolean handled = processClaimedMatch(job);
            if (handled) {
                // Terminal state written or retry scheduled in PostgreSQL → ACK.
                ack(message);
            }
            // else: DB state transition failed → keep the message in the PEL for redelivery
        } catch (RuntimeException exception) {
            log.error("Stream 消息处理异常，matchId={}，类型={}",
                    matchId, exception.getClass().getSimpleName());
        }
    }

    /**
     * Process a claimed match: read data, call AI, compute decision, complete or retry.
     * Returns true when the message can be ACKed — either the match reached a terminal
     * state (SUCCEEDED/FAILED) or a retry was successfully scheduled in PostgreSQL
     * (next_attempt_at is the recovery path, so the Redis PEL must not keep it).
     * Returns false only when a retry state transition failed and the message must
     * stay in the PEL for redelivery.
     */
    private boolean processClaimedMatch(ProcessJob job) {
        UUID userId = job.ownerUserId();
        UUID matchId = job.matchId();
        UUID leaseToken = job.leaseToken();
        int attemptNumber = job.attemptNumber();

        try {
            // Defensive check; the DB claim functions already reject attempt_count >= maxAttempts
            if (attemptNumber > maxAttempts) {
                if (completeMatch(userId, matchId, leaseToken, "FAILED",
                        null, null, null, List.of(), List.of(), null,
                        null, null, null, null, null, null,
                        "MAX_ATTEMPTS_EXCEEDED", "已达最大重试次数（" + maxAttempts + "）")) {
                    audit.append(userId, "SYSTEM", null, "JOB_ANALYSIS_FAILED",
                            "JOB_MATCH", matchId, "FAILED", null, null, "Worker",
                            Map.of("errorCode", "MAX_ATTEMPTS_EXCEEDED",
                                    "attemptNumber", attemptNumber));
                }
                return true;
            }

            // Read all data within tenant context
            var jobData = inTenant(userId, () ->
                    matchRepo.readJobData(userId, job.jobPostId()));
            var resumeData = inTenant(userId, () ->
                    matchRepo.readResumeData(userId, job.resumeId()));
            var preferenceData = inTenant(userId, () ->
                    matchRepo.readPreferenceData(userId, job.preferenceId()));

            // Decrypt resume text
            String resumeText = decryptResume(resumeData);
            if (resumeText == null) {
                if (completeMatch(userId, matchId, leaseToken, "FAILED",
                        null, null, null, List.of(), List.of(), null,
                        null, null, null, null, null, null,
                        "RESUME_TEXT_MISSING", "简历文本为空或解密失败")) {
                    audit.append(userId, "SYSTEM", null, "JOB_ANALYSIS_FAILED",
                            "JOB_MATCH", matchId, "FAILED", null, null, "Worker",
                            Map.of("errorCode", "RESUME_TEXT_MISSING",
                                    "attemptNumber", attemptNumber));
                }
                return true;
            }

            // Sanitize PII before sending to AI
            String sanitizedText = sanitizePii(resumeText);

            List<String> targetTitles = parseList(preferenceData.targetTitlesJson());
            List<String> preferredCompanies = parseList(preferenceData.preferredCompaniesJson());
            List<String> excludedCompanies = parseList(preferenceData.excludedCompaniesJson());
            List<String> excludedKeywords = parseList(preferenceData.excludedKeywordsJson());

            AiMatchClient.MatchRequest aiRequest = new AiMatchClient.MatchRequest(
                    jobData.title(), jobData.companyName(), jobData.description(),
                    sanitizedText, targetTitles, preferredCompanies,
                    excludedCompanies, excludedKeywords
            );

            AiMatchClient.MatchResponse aiResponse;
            try {
                aiResponse = aiClient.analyze(aiRequest);
            } catch (AiMatchException exception) {
                if (exception.retryable() && attemptNumber < maxAttempts) {
                    // Schedule retry with bounded exponential backoff. When the transition
                    // succeeds, PostgreSQL next_attempt_at becomes the recovery path → ACK.
                    int delay = properties.retryDelayForAttempt(attemptNumber);
                    boolean scheduled = inTenant(userId, () ->
                            matchRepo.retryMatchLater(userId, matchId, leaseToken, delay));
                    if (scheduled) {
                        log.warn("AI 匹配暂时失败，已安排重试 ({}/{}): matchId={}, code={}, 延迟={}s",
                                attemptNumber, maxAttempts, matchId, exception.code(), delay);
                        return true;
                    }
                    log.error("AI 匹配重试安排失败，保留消息在 PEL 等待重投: matchId={}, code={}",
                            matchId, exception.code());
                    return false;
                }
                // Non-retryable or max attempts exhausted → FAILED.
                // Invalid model output never computes a decision and never yields APPLY.
                if (completeMatch(userId, matchId, leaseToken, "FAILED",
                        null, null, null, List.of(), List.of(), null,
                        null, null, null, null, null, null,
                        exception.code(),
                        truncate(sanitizePii(exception.getMessage()), 500))) {
                    audit.append(userId, "SYSTEM", null, "JOB_ANALYSIS_FAILED",
                            "JOB_MATCH", matchId, "FAILED", null, null, "Worker",
                            Map.of("errorCode", exception.code(),
                                    "attemptNumber", attemptNumber));
                }
                return true;
            }

            // Compute decision server-side (model does NOT decide recommendation level)
            String decision = computeDecision(
                    aiResponse.score(), jobData.companyName(), preferredCompanies,
                    preferenceData.reviewThreshold(),
                    preferenceData.priorityApplyThreshold(),
                    preferenceData.applyThreshold()
            );

            // Secondary PII sanitization on model output before persisting
            String safeSummary = sanitizePii(aiResponse.summary());
            List<String> safeStrengths = aiResponse.strengths() == null ? List.of() :
                    aiResponse.strengths().stream().map(MatchWorker::sanitizePii).toList();
            List<String> safeRisks = aiResponse.risks() == null ? List.of() :
                    aiResponse.risks().stream().map(MatchWorker::sanitizePii).toList();
            String safeGreeting = sanitizePii(aiResponse.greeting());

            boolean completed = completeMatch(userId, matchId, leaseToken, "SUCCEEDED",
                    (short) aiResponse.score(), decision,
                    safeSummary, safeStrengths, safeRisks,
                    safeGreeting,
                    aiResponse.modelProvider(), aiResponse.modelName(),
                    aiResponse.promptVersion(),
                    aiResponse.inputTokens(), aiResponse.outputTokens(),
                    aiResponse.durationMs(),
                    null, null);

            if (completed) {
                audit.append(userId, "SYSTEM", null, "JOB_ANALYSIS_SUCCEEDED",
                        "JOB_MATCH", matchId, "SUCCESS", null, null, "Worker",
                        Map.of("score", aiResponse.score(), "decision", decision));
            }
            return true;

        } catch (RuntimeException exception) {
            log.error("AI 匹配处理异常 matchId={}，类型={}",
                    matchId, exception.getClass().getSimpleName());
            if (attemptNumber < maxAttempts) {
                int delay = properties.retryDelayForAttempt(attemptNumber);
                boolean scheduled = inTenant(userId, () ->
                        matchRepo.retryMatchLater(userId, matchId, leaseToken, delay));
                return scheduled;
            }
            if (completeMatch(userId, matchId, leaseToken, "FAILED",
                    null, null, null, List.of(), List.of(), null,
                    null, null, null, null, null, null,
                    "INTERNAL_ERROR", "AI 匹配处理发生内部错误，请稍后重试")) {
                audit.append(userId, "SYSTEM", null, "JOB_ANALYSIS_FAILED",
                        "JOB_MATCH", matchId, "FAILED", null, null, "Worker",
                        Map.of("errorCode", "INTERNAL_ERROR",
                                "attemptNumber", attemptNumber));
            }
            return true;
        }
    }

    private String decryptResume(MatchWorkerRepository.ResumeData data) {
        if (data.extractedTextCiphertext() == null || data.extractedTextNonce() == null) {
            return null;
        }
        try {
            var encrypted = new DataEncryptionService.EncryptedData(
                    data.extractedTextCiphertext(), data.extractedTextNonce(), data.encryptionKeyId()
            );
            byte[] plaintext = encryption.decrypt(encrypted,
                    "resume-text:" + data.id() + ":" + data.textVersion());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            log.error("简历文本解密失败 resumeId={}", data.id());
            return null;
        }
    }

    // ---- PII sanitization ----

    /**
     * Sanitize PII from text before sending to AI model or persisting.
     * Removes phone numbers, emails, and 18-digit Chinese ID numbers.
     */
    static String sanitizePii(String text) {
        if (text == null) {
            return "";
        }
        // Chinese and international mobile phone numbers
        String result = text.replaceAll("1[3-9]\\d{9}", "[手机号已隐藏]");
        result = result.replaceAll("\\+\\d{1,3}[\\s-]?\\d{3,14}", "[国际号码已隐藏]");
        // 18-digit Chinese ID numbers
        result = result.replaceAll("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]",
                "[身份证号已隐藏]");
        // Email addresses
        result = result.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                "[邮箱已隐藏]");
        return result;
    }

    // ---- Decision computation ----

    private String computeDecision(
            int score, String companyName, List<String> preferredCompanies,
            short reviewThreshold, short priorityApplyThreshold, short applyThreshold
    ) {
        if (isPriorityCompany(companyName, preferredCompanies)) {
            return score >= priorityApplyThreshold ? "APPLY"
                    : score >= reviewThreshold ? "REVIEW" : "SKIP";
        }
        return score >= applyThreshold ? "APPLY"
                : score >= reviewThreshold ? "REVIEW" : "SKIP";
    }

    private boolean isPriorityCompany(String companyName, List<String> preferredCompanies) {
        if (companyName == null || preferredCompanies == null || preferredCompanies.isEmpty()) {
            return false;
        }
        String normalized = companyName.trim().toLowerCase(Locale.ROOT);
        return preferredCompanies.stream()
                .anyMatch(pc -> {
                    String pcNorm = pc.toLowerCase(Locale.ROOT);
                    return normalized.contains(pcNorm) || pcNorm.contains(normalized);
                });
    }

    // ---- Helpers ----

    private boolean completeMatch(
            UUID userId, UUID matchId, UUID leaseToken,
            String status, Short score, String decision,
            String summary, List<String> strengths, List<String> risks,
            String greeting,
            String modelProvider, String modelName, String promptVersion,
            Integer inputTokens, Integer outputTokens, Integer durationMs,
            String errorCode, String errorMessage
    ) {
        return inTenant(userId, () ->
                matchRepo.completeMatch(
                        userId, matchId, leaseToken, status, score, decision,
                        summary, strengths, risks, greeting,
                        modelProvider, modelName, promptVersion,
                        inputTokens, outputTokens, durationMs,
                        errorCode, errorMessage
                )
        );
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String str = value.toString();
        return str.isBlank() ? null : str;
    }

    private void ack(MapRecord<String, Object, Object> message) {
        try {
            redis.opsForStream().acknowledge(streamKey, consumerGroup,
                    message.getId().getValue());
        } catch (RuntimeException ignored) {
        }
    }

    private <T> T inTenant(UUID userId, java.util.function.Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
