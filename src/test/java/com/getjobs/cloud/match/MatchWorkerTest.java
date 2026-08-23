package com.getjobs.cloud.match;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.ai.AiMatchClient;
import com.getjobs.cloud.ai.AiMatchException;
import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.crypto.DataEncryptionService;
import com.getjobs.cloud.match.MatchWorkerRepository.*;
import com.getjobs.cloud.quota.QuotaConstants;
import com.getjobs.cloud.quota.QuotaService;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MatchWorkerTest {

    @Mock private MatchWorkerRepository matchRepo;
    @Mock private DataEncryptionService encryption;
    @Mock private AiMatchClient aiClient;
    @Mock private TenantContextExecutor tenants;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private StringRedisTemplate redis;
    @Mock private StreamOperations<String, Object, Object> streamOps;
    @Mock private AuditWriter audit;
    @Mock private QuotaService quotas;

    private AiMatchProperties properties;
    private MatchWorker worker;

    private final UUID userId = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final UUID leaseToken = UUID.randomUUID();
    private final UUID jobPostId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID preferenceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new AiMatchProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setLeaseSeconds(600);
        properties.setMaxAttempts(3);
        properties.setRetryBaseDelaySeconds(5);
        properties.setRetryMaxDelaySeconds(300);
        properties.setConsumerName("test-worker");

        // Redis stubs needed by constructor
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(anyString(), anyString())).thenReturn("OK");

        // Default lenient stubs to avoid StrictStubbing issues
        lenient().when(encryption.decrypt(any(), anyString()))
                .thenReturn("测试简历文本".getBytes(StandardCharsets.UTF_8));
        lenient().when(tenants.execute(any(UUID.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        worker = new MatchWorker(matchRepo, encryption, aiClient, tenants,
                transactionManager, redis, new ObjectMapper(), audit, quotas, properties);
    }

    @Test
    void redisConsumerLogDoesNotExposeExceptionMessage() {
        String sensitiveMessage = "redis://example.invalid/resume-content?marker=SENSITIVE_EXCEPTION_TEXT";
        Logger logger = (Logger) LoggerFactory.getLogger(MatchWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            when(redis.opsForStream()).thenThrow(new RuntimeException(sensitiveMessage));

            worker.consumeFromRedis();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("异常类型=RuntimeException"))
                    .allMatch(message -> !message.contains(sensitiveMessage));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ---- Decision tests (thresholds) ----

    private void setupAllData(String companyName, List<String> preferredCompanies,
                               short review, short priority, short apply) {
        when(matchRepo.readJobData(any(), any()))
                .thenReturn(new JobData("Java工程师", companyName, "岗位描述文本"));
        when(matchRepo.readResumeData(any(), any()))
                .thenReturn(new ResumeData(resumeId, new byte[]{1}, new byte[]{2}, "v1", 1));
        when(matchRepo.readPreferenceData(any(), any()))
                .thenReturn(new PreferenceData(1, "[\"后端开发\"]", "[\"优先科技\"]",
                        "[]", "[]", review, priority, apply));
    }

    @Test
    void normalCompanyAppliesStandardThresholds() {
        setupAllData("示例公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenReturn(
                new AiMatchClient.MatchResponse(80, "匹配度较高", List.of("技术匹配"),
                        List.of("薪资略低"), "您好", "openai", "gpt-4.1-mini", "v1", 500, 200, 3000));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        ProcessJob job = buildProcessJob(1);
        boolean terminal = invokeProcessClaimedMatch(job);

        assertThat(terminal).isTrue();
        ArgumentCaptor<String> decisionCaptor = ArgumentCaptor.forClass(String.class);
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("SUCCEEDED"), eq((short) 80), decisionCaptor.capture(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), isNull(), isNull());
        assertThat(decisionCaptor.getValue()).isEqualTo("APPLY");
    }

    @Test
    void priorityCompanyUsesLowerThresholds() {
        setupAllData("优先科技公司", List.of("优先科技"), (short) 50, (short) 55, (short) 70);
        when(aiClient.analyze(any())).thenReturn(
                new AiMatchClient.MatchResponse(58, "基本匹配", List.of(), List.of(), "您好",
                        "openai", "gpt-4.1-mini", "v1", 300, 100, 2000));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        ProcessJob job = buildProcessJob(1);
        boolean terminal = invokeProcessClaimedMatch(job);

        assertThat(terminal).isTrue();
        ArgumentCaptor<String> decisionCaptor = ArgumentCaptor.forClass(String.class);
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("SUCCEEDED"), any(), decisionCaptor.capture(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), isNull(), isNull());
        assertThat(decisionCaptor.getValue()).isEqualTo("APPLY");
    }

    // Boundary tests using a helper
    @Test void scoreAt59WithReview60IsSkip() {
        scoreOutcome(59, (short) 60, (short) 65, (short) 75, "普通公司", List.of(), "SKIP");
    }
    @Test void scoreAt60WithReview60IsReview() {
        scoreOutcome(60, (short) 60, (short) 65, (short) 75, "普通公司", List.of(), "REVIEW");
    }
    @Test void priorityScoreAt64WithPriority65IsReview() {
        scoreOutcome(64, (short) 60, (short) 65, (short) 75, "优先科技", List.of("优先科技"), "REVIEW");
    }
    @Test void priorityScoreAt65WithPriority65IsApply() {
        scoreOutcome(65, (short) 60, (short) 65, (short) 75, "优先科技", List.of("优先科技"), "APPLY");
    }
    @Test void scoreAt74WithApply75IsReview() {
        scoreOutcome(74, (short) 60, (short) 65, (short) 75, "普通公司", List.of(), "REVIEW");
    }
    @Test void scoreAt75WithApply75IsApply() {
        scoreOutcome(75, (short) 60, (short) 65, (short) 75, "普通公司", List.of(), "APPLY");
    }

    private void scoreOutcome(int score, short review, short priority, short apply,
                              String company, List<String> preferred, String expectedDecision) {
        setupAllData(company, preferred, review, priority, apply);
        when(aiClient.analyze(any())).thenReturn(
                new AiMatchClient.MatchResponse(score, "分析结果", List.of(), List.of(), null,
                        "openai", "gpt-4.1-mini", "v1", 300, 100, 2000));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        invokeProcessClaimedMatch(buildProcessJob(1));
        ArgumentCaptor<String> decisionCaptor = ArgumentCaptor.forClass(String.class);
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("SUCCEEDED"), any(), decisionCaptor.capture(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), isNull(), isNull());
        assertThat(decisionCaptor.getValue()).isEqualTo(expectedDecision);
    }

    // ---- PII tests ----

    @Test
    void sanitizesPiiFromResumeTextBeforeSendingToAi() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        // Override encryption to return text with PII
        when(encryption.decrypt(any(), anyString()))
                .thenReturn("张三 13812345678 test@example.com".getBytes(StandardCharsets.UTF_8));
        when(aiClient.analyze(any())).thenReturn(
                new AiMatchClient.MatchResponse(80, "匹配度高", List.of(), List.of(), "您好",
                        "openai", "gpt-4.1-mini", "v1", 300, 100, 2000));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        invokeProcessClaimedMatch(buildProcessJob(1));

        ArgumentCaptor<AiMatchClient.MatchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiMatchClient.MatchRequest.class);
        verify(aiClient).analyze(requestCaptor.capture());
        String sentResume = requestCaptor.getValue().resumeText();
        assertThat(sentResume).doesNotContain("13812345678");
        assertThat(sentResume).doesNotContain("test@example.com");
        assertThat(sentResume).contains("[手机号已隐藏]");
        assertThat(sentResume).contains("[邮箱已隐藏]");
    }

    @Test
    void sanitizesPiiFromErrorMessageStoredInDatabase() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_RESPONSE_INVALID",
                        "响应包含邮箱 test@example.com 和手机 13812345678", false));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        invokeProcessClaimedMatch(buildProcessJob(1));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("FAILED"), isNull(), isNull(), isNull(),
                anyList(), anyList(), isNull(),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                anyString(), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).doesNotContain("test@example.com");
        assertThat(msgCaptor.getValue()).doesNotContain("13812345678");
    }

    // ---- Retry/Failure behavior ----

    @Test
    void retryableErrorSchedulesRetryAndReturnsAckTrue() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_SERVICE_UNAVAILABLE", "临时不可用", true));
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);

        // PostgreSQL next_attempt_at becomes the recovery path → message must be ACKed
        boolean ack = invokeProcessClaimedMatch(buildProcessJob(1));

        assertThat(ack).isTrue();
        verify(matchRepo).retryMatchLater(any(), any(), any(), anyInt());
        verify(matchRepo, never()).completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void failedRetryTransitionReturnsAckFalseToStayInPel() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_SERVICE_UNAVAILABLE", "临时不可用", true));
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(false);

        // DB transition failed → keep the message in the PEL for redelivery
        boolean ack = invokeProcessClaimedMatch(buildProcessJob(1));

        assertThat(ack).isFalse();
        verify(matchRepo).retryMatchLater(any(), any(), any(), anyInt());
    }

    @Test
    void nonRetryableErrorGoesToFailedAndIsTerminal() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_RESPONSE_INVALID", "无效响应", false));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean terminal = invokeProcessClaimedMatch(buildProcessJob(1));

        assertThat(terminal).isTrue();
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("FAILED"), isNull(), isNull(), isNull(),
                anyList(), anyList(), isNull(),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq("AI_RESPONSE_INVALID"), anyString());
        verify(matchRepo, never()).retryMatchLater(any(), any(), any(), anyInt());
    }

    @Test
    void exceedsMaxAttemptsGoesToFailedWithoutCallingAiAndWritesAudit() {
        // attemptNumber=4 > maxAttempts=3 → early exit
        ProcessJob job = buildProcessJob(4);
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean terminal = invokeProcessClaimedMatch(job);

        assertThat(terminal).isTrue();
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("FAILED"), isNull(), isNull(), isNull(),
                anyList(), anyList(), isNull(),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq("MAX_ATTEMPTS_EXCEEDED"), anyString());
        verify(aiClient, never()).analyze(any());
        verify(audit).append(eq(userId), eq("SYSTEM"), isNull(), eq("JOB_ANALYSIS_FAILED"),
                eq("JOB_MATCH"), eq(matchId), eq("FAILED"), isNull(), isNull(), eq("Worker"),
                anyMap());
    }

    @Test
    void invalidAiOutputFailsMatchWithoutDecision() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_RESPONSE_INVALID", "AI 响应包含未预期字段", false));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean terminal = invokeProcessClaimedMatch(buildProcessJob(1));

        assertThat(terminal).isTrue();
        verify(matchRepo, never()).retryMatchLater(any(), any(), any(), anyInt());
        // Decision is never computed for invalid output: FAILED with null decision
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("FAILED"), isNull(), isNull(), isNull(),
                anyList(), anyList(), isNull(),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq("AI_RESPONSE_INVALID"), anyString());
        verify(audit).append(eq(userId), eq("SYSTEM"), isNull(), eq("JOB_ANALYSIS_FAILED"),
                eq("JOB_MATCH"), eq(matchId), eq("FAILED"), isNull(), isNull(), eq("Worker"),
                anyMap());
    }

    @Test
    void unknownRuntimeExceptionAtMaxAttemptsStoresGenericMessageAndWritesAudit() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new RuntimeException("internal details test@example.com"));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        // attemptNumber=3 equals maxAttempts → no retry, straight to FAILED
        boolean terminal = invokeProcessClaimedMatch(buildProcessJob(3));

        assertThat(terminal).isTrue();
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(matchRepo).completeMatch(any(), any(), any(),
                eq("FAILED"), isNull(), isNull(), isNull(),
                anyList(), anyList(), isNull(),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq("INTERNAL_ERROR"), msgCaptor.capture());
        // Internal exception messages must never be persisted
        assertThat(msgCaptor.getValue()).doesNotContain("internal details", "test@example.com");
        verify(audit).append(eq(userId), eq("SYSTEM"), isNull(), eq("JOB_ANALYSIS_FAILED"),
                eq("JOB_MATCH"), eq(matchId), eq("FAILED"), isNull(), isNull(), eq("Worker"),
                anyMap());
    }

    // ---- Quota settlement ----

    @Test
    void succeededMatchCommitsQuotaReservationWithPersistedKey() {
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.of("ai:reservation-1"));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean completed = invokeCompleteMatch("SUCCEEDED", 80, "APPLY", null, null);

        assertThat(completed).isTrue();
        verify(quotas).commitReservation(userId, QuotaConstants.RESOURCE_AI_ANALYSIS,
                "ai:reservation-1", QuotaConstants.REFERENCE_JOB_MATCH, matchId,
                QuotaConstants.REASON_AI_ANALYSIS_COMMIT);
        verify(quotas, never()).releaseReservation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalFailedMatchReleasesQuotaReservationWithPersistedKey() {
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.of("ai:reservation-2"));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean completed = invokeCompleteMatch("FAILED", null, null, "AI_ERROR", "失败");

        assertThat(completed).isTrue();
        verify(quotas).releaseReservation(userId, QuotaConstants.RESOURCE_AI_ANALYSIS,
                "ai:reservation-2", QuotaConstants.REFERENCE_JOB_MATCH, matchId,
                QuotaConstants.REASON_AI_ANALYSIS_RELEASE);
        verify(quotas, never()).commitReservation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void historicalMatchWithNullKeySkipsQuotaSettlement() {
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.empty());
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);

        boolean completed = invokeCompleteMatch("SUCCEEDED", 80, "APPLY", null, null);

        assertThat(completed).isTrue();
        verify(quotas, never()).commitReservation(any(), any(), any(), any(), any(), any());
        verify(quotas, never()).releaseReservation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedMatchWriteDoesNotSettleQuota() {
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.of("ai:reservation-3"));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(false);

        boolean completed = invokeCompleteMatch("SUCCEEDED", 80, "APPLY", null, null);

        assertThat(completed).isFalse();
        verify(quotas, never()).commitReservation(any(), any(), any(), any(), any(), any());
        verify(quotas, never()).releaseReservation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryableErrorNeverSettlesQuotaUntilTerminalState() {
        setupAllData("测试公司", List.of(), (short) 60, (short) 65, (short) 75);
        when(aiClient.analyze(any())).thenThrow(
                new AiMatchException("AI_SERVICE_UNAVAILABLE", "临时不可用", true));
        when(matchRepo.retryMatchLater(any(), any(), any(), anyInt())).thenReturn(true);
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.of("ai:reservation-4"));

        boolean ack = invokeProcessClaimedMatch(buildProcessJob(1));

        assertThat(ack).isTrue();
        verify(quotas, never()).commitReservation(any(), any(), any(), any(), any(), any());
        verify(quotas, never()).releaseReservation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void settlementErrorPropagatesAndDoesNotSwallowExceptions() {
        when(matchRepo.findQuotaReservationKey(userId, matchId))
                .thenReturn(Optional.of("ai:reservation-5"));
        when(matchRepo.completeMatch(any(), any(), any(), any(), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("quota boom")).when(quotas)
                .commitReservation(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> invokeCompleteMatch("SUCCEEDED", 80, "APPLY", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("quota boom");
        // 终态写入已发生，但额度结算异常必须继续向外传播（事务回滚），不能吞掉。
        verify(matchRepo).completeMatch(any(), any(), any(), eq("SUCCEEDED"), any(), any(), any(),
                anyList(), anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    // ---- Helpers ----

    /**
     * Invoke private processClaimedMatch via reflection.
     * Sets up mock TransactionTemplate and TenantContextExecutor to bypass
     * real transaction management and RLS setup.
     */
    private boolean invokeProcessClaimedMatch(ProcessJob job) {
        try {
            // Replace TransactionTemplate with a mock that executes callbacks directly
            TransactionTemplate mockTx = mock(TransactionTemplate.class);
            when(mockTx.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class));
            });

            var txField = MatchWorker.class.getDeclaredField("transactions");
            txField.setAccessible(true);
            txField.set(worker, mockTx);

            var method = MatchWorker.class.getDeclaredMethod("processClaimedMatch", ProcessJob.class);
            method.setAccessible(true);
            return (boolean) method.invoke(worker, job);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke processClaimedMatch", e);
        }
    }

    /**
     * Invoke private completeMatch via reflection with the same mock transaction
     * setup used by {@link #invokeProcessClaimedMatch}.
     */
    private boolean invokeCompleteMatch(
            String status, Integer score, String decision, String errorCode, String errorMessage
    ) {
        try {
            TransactionTemplate mockTx = mock(TransactionTemplate.class);
            when(mockTx.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class));
            });

            var txField = MatchWorker.class.getDeclaredField("transactions");
            txField.setAccessible(true);
            txField.set(worker, mockTx);

            var method = MatchWorker.class.getDeclaredMethod("completeMatch",
                    UUID.class, UUID.class, UUID.class, String.class, Short.class, String.class,
                    String.class, List.class, List.class, String.class,
                    String.class, String.class, String.class,
                    Integer.class, Integer.class, Integer.class,
                    String.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(worker, userId, matchId, leaseToken,
                    status, score == null ? null : (short) (int) score, decision,
                    null, List.of(), List.of(), null,
                    null, null, null,
                    null, null, null,
                    errorCode, errorMessage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke completeMatch", e);
        }
    }

    private ProcessJob buildProcessJob(int attemptNumber) {
        return new ProcessJob(matchId, userId, jobPostId, resumeId, preferenceId,
                leaseToken, attemptNumber);
    }
}
