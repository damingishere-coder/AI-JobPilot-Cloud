package com.getjobs.cloud.match;

import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.jobs.JobModels;
import com.getjobs.cloud.jobs.JobRepository;
import com.getjobs.cloud.match.MatchRepository.MatchRecord;
import com.getjobs.cloud.preference.PreferenceRepository;
import com.getjobs.cloud.preference.PreferenceRepository.PreferenceRecord;
import com.getjobs.cloud.quota.QuotaConstants;
import com.getjobs.cloud.quota.QuotaService;
import com.getjobs.cloud.resume.ResumeRecord;
import com.getjobs.cloud.resume.ResumeRepository;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MatchService AI 分析入队额度预占行为：新请求 reserve 一次、既有 replay 零次、
 * force 仅真正 requeue 的 winner 才 reserve，额度不足整个事务回滚。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MatchServiceQuotaTest {

    @Mock private MatchRepository matches;
    @Mock private MatchOutboxRepository outbox;
    @Mock private JobRepository jobs;
    @Mock private ResumeRepository resumes;
    @Mock private PreferenceRepository preferences;
    @Mock private TenantContextExecutor tenants;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private QuotaService quotas;

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID preferenceId = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    private MatchService service;
    private AiMatchProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiMatchProperties();
        aiProperties.setProvider("openai");
        aiProperties.setModel("gpt-4.1-mini");
        aiProperties.setPromptVersion("v1");

        lenient().when(tenants.execute(any(UUID.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });

        service = new MatchService(matches, outbox, jobs, resumes, preferences,
                tenants, transactionManager, aiProperties, quotas, clock);
        swapTransactions(service);
    }

    private void stubNewAnalysisInputs() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString())).thenReturn(Optional.empty());
        when(matches.insert(any(), eq(userId), eq(jobId), eq(resumeId), eq(preferenceId),
                anyString(), anyString(), anyString())).thenReturn(matchRecord("PENDING", null));
    }

    @Test
    void newRequestReservesQuotaOnceWithRandomAiKey() {
        stubNewAnalysisInputs();

        service.analyze(userId, jobId, false);

        // Capture the method-local matchId and the generated base key from insert.
        ArgumentCaptor<UUID> matchIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(matches).insert(matchIdCaptor.capture(), eq(userId), eq(jobId), eq(resumeId),
                eq(preferenceId), eq("PENDING"), anyString(), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("ai:");
        assertThat(keyCaptor.getValue().length()).isLessThanOrEqualTo(110);

        // The same random key is reserved exactly once for this match.
        verify(quotas, times(1)).reserve(eq(userId), eq(QuotaConstants.RESOURCE_AI_ANALYSIS),
                eq(keyCaptor.getValue()), eq(QuotaConstants.REFERENCE_JOB_MATCH),
                eq(matchIdCaptor.getValue()), eq(QuotaConstants.REASON_AI_ANALYSIS_RESERVE));
        // Outbox write happens after the successful reserve.
        verify(outbox).insert(eq(userId), eq(matchIdCaptor.getValue()),
                eq("JOB_ANALYSIS_REQUESTED"), anyString());
    }

    @Test
    void existingSucceededReplayNeverCallsQuota() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.of(matchRecord("SUCCEEDED", "ai:old-key")));

        service.analyze(userId, jobId, false);

        verify(quotas, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(matches, never()).forceRequeue(any(), any());
        verify(matches, never()).updateQuotaReservationKey(any(), any(), any());
        verify(outbox, never()).insert(any(), any(), anyString(), anyString());
    }

    @Test
    void existingProcessingReplayNeverCallsQuota() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.of(matchRecord("PROCESSING", "ai:old-key")));

        service.analyze(userId, jobId, false);

        verify(quotas, never()).reserve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedWithoutForceDoesNotReserve() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.of(matchRecord("FAILED", "ai:old-key")));

        service.analyze(userId, jobId, false);

        verify(quotas, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(matches, never()).forceRequeue(any(), any());
    }

    @Test
    void failedForceWinnerReservesWithFreshKeyAndPersistsIt() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.of(matchRecord("FAILED", "ai:old-key")));
        when(matches.forceRequeue(userId, matchId)).thenReturn(true);
        when(matches.updateQuotaReservationKey(eq(userId), eq(matchId), startsWith("ai:")))
                .thenReturn(true);

        service.analyze(userId, jobId, true);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(quotas, times(1)).reserve(eq(userId), eq(QuotaConstants.RESOURCE_AI_ANALYSIS),
                keyCaptor.capture(), eq(QuotaConstants.REFERENCE_JOB_MATCH), eq(matchId),
                eq(QuotaConstants.REASON_AI_ANALYSIS_RESERVE));
        assertThat(keyCaptor.getValue()).startsWith("ai:").doesNotStartWith("ai:old-key");
        verify(matches).updateQuotaReservationKey(eq(userId), eq(matchId), eq(keyCaptor.getValue()));
        verify(outbox, never()).insert(any(), any(), anyString(), anyString());
    }

    @Test
    void failedForceLoserDoesNotReserve() {
        when(jobs.find(userId, jobId)).thenReturn(Optional.of(jobDetail()));
        when(resumes.findCurrent(userId)).thenReturn(Optional.of(resumeRecord()));
        when(preferences.findCurrent(userId, false)).thenReturn(Optional.of(preferenceRecord()));
        when(matches.findByFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.of(matchRecord("FAILED", "ai:old-key")));
        when(matches.forceRequeue(userId, matchId)).thenReturn(false);

        service.analyze(userId, jobId, true);

        verify(quotas, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(matches, never()).updateQuotaReservationKey(any(), any(), any());
    }

    @Test
    void quotaExceededRollsBackMatchAndOutbox() {
        stubNewAnalysisInputs();
        when(quotas.reserve(any(), any(), any(), any(), any(), any())).thenThrow(new ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", "本月 AI 分析额度已用完，请升级套餐或下月再试"));

        assertThatThrownBy(() -> service.analyze(userId, jobId, false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.code()).isEqualTo("QUOTA_EXCEEDED");
                });
        // Outbox 写入发生在 reserve 之后；reserve 抛错时它绝不会被执行。
        verify(outbox, never()).insert(any(), any(), anyString(), anyString());
    }

    // ---- helpers ----

    private void swapTransactions(MatchService service) {
        try {
            TransactionTemplate mockTx = mock(TransactionTemplate.class);
            when(mockTx.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class));
            });
            var field = MatchService.class.getDeclaredField("transactions");
            field.setAccessible(true);
            field.set(service, mockTx);
        } catch (Exception e) {
            throw new RuntimeException("Failed to swap MatchService transactions", e);
        }
    }

    private JobModels.JobDetail jobDetail() {
        return new JobModels.JobDetail(
                jobId, "BOSS", "ext-1", "Java工程师", "示例公司", null, "上海", "3-5年", "本科",
                "岗位描述", "https://example.com/job", Map.of(), List.of(), List.of(), "ACTIVE",
                Instant.now(), Instant.now(), null, null);
    }

    private ResumeRecord resumeRecord() {
        return new ResumeRecord(
                resumeId, userId, "resume.pdf", "storage-key", "application/pdf", 1024L, "hash",
                "PARSED", null, null, null, "key-v1", 1, true, 1,
                Instant.now(), null, null, Instant.now(), Instant.now());
    }

    private PreferenceRecord preferenceRecord() {
        return new PreferenceRecord(
                preferenceId, userId, 1, List.of("后端开发"), List.of("上海"),
                BigDecimal.valueOf(10), BigDecimal.valueOf(30),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), (short) 60, (short) 65, (short) 75, Instant.now());
    }

    private MatchRecord matchRecord(String status, String quotaKey) {
        return new MatchRecord(
                matchId, userId, jobId, resumeId, preferenceId, status,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, 0, Instant.now(), null, quotaKey);
    }
}
