package com.getjobs.cloud.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.UserRole;
import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.delivery.DeliveryRepository.EventRow;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskRecord;
import com.getjobs.cloud.jobs.JobModels;
import com.getjobs.cloud.jobs.JobRepository;
import com.getjobs.cloud.match.MatchRepository;
import com.getjobs.cloud.match.MatchRepository.MatchRecord;
import com.getjobs.cloud.plugin.PluginRepository;
import com.getjobs.cloud.quota.QuotaConstants;
import com.getjobs.cloud.quota.QuotaService;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P9 小批次 1C：投递确认额度扣减（Mock 化单元测试）。
 *
 * <p>验证：createTask 零次 consume；首次真实 confirm 在状态写前恰好 consume
 * 一次；相同 key replay 零次新增 consume；额度不足时不调用
 * confirmTask/insertEvent/audit；非 replay 的二次确认因状态不允许也不扣；
 * 构造器完整注入 QuotaService（本类全部用例通过完整构造器组装服务）。
 * 插件 SUCCESS/FAILED/PAUSED 回传路径不包含任何 quota 调用，由代码结构保证。</p>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DeliveryConfirmQuotaTest {

    @Mock private DeliveryRepository tasks;
    @Mock private JobRepository jobs;
    @Mock private MatchRepository matches;
    @Mock private PluginRepository devices;
    @Mock private TenantContextExecutor tenants;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AuditWriter audit;
    @Mock private AuditLogService auditLogs;
    @Mock private QuotaService quotas;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID jobMatchId = UUID.randomUUID();
    private final UUID jobPostId = UUID.randomUUID();
    private final String idempotencyKey = "confirm-quota-key-1";
    private final String requestId = "req-quota-1";
    private final RequestMetadata request = new RequestMetadata("127.0.0.1", "Chrome/120");
    private final UserRole role = UserRole.USER;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        lenient().when(tenants.execute(any(UUID.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        service = new DeliveryService(
                tasks, jobs, matches, devices, tenants, transactionManager,
                new DeliveryProperties(), audit, auditLogs, quotas,
                Clock.systemUTC(), objectMapper);
        swapTransactions(service);
    }

    // ---- 1. createTask 零次 consume ----

    @Test
    void createTaskNeverConsumesQuota() {
        when(matches.findById(userId, jobMatchId))
                .thenReturn(Optional.of(matchRecord("SUCCEEDED", "APPLY")));
        when(jobs.find(userId, jobPostId)).thenReturn(Optional.of(jobDetail()));
        when(tasks.findByKeyHash(eq(userId), anyString())).thenReturn(Optional.empty());
        when(tasks.findByMatch(eq(userId), eq(jobMatchId))).thenReturn(Optional.empty());
        when(tasks.findActiveForJob(eq(userId), eq(jobPostId))).thenReturn(Optional.empty());
        // insertTask 内部生成随机 taskId：回传实际传入的第一个参数作为新建任务 id。
        when(tasks.insertTask(any(), eq(userId), eq(jobPostId), eq(jobMatchId), eq("WAITING_CONFIRM"),
                any(), anyString(), anyString())).thenAnswer(invocation ->
                Optional.of(invocation.getArgument(0, UUID.class)));
        when(tasks.findById(eq(userId), any(UUID.class)))
                .thenReturn(Optional.of(taskRecord("WAITING_CONFIRM", 1)));

        service.createTask(userId, jobMatchId, jobPostId, "create-key-1", "req-create");

        // 创建待确认任务只是登记清单：绝不触碰额度。
        verifyNoInteractions(quotas);
        verify(tasks).insertTask(any(), eq(userId), eq(jobPostId), eq(jobMatchId), eq("WAITING_CONFIRM"),
                any(), anyString(), anyString());
    }

    // ---- 2. 首次真实 confirm：consume 一次且在状态写前 ----

    @Test
    void firstRealConfirmConsumesOnceBeforeStateWrite() {
        when(tasks.findByIdForUpdate(userId, taskId))
                .thenReturn(Optional.of(taskRecord("WAITING_CONFIRM", 1)));
        when(tasks.findEvent(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.findEventByKeyHash(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.confirmTask(userId, taskId, 1, null)).thenReturn(true);
        when(tasks.findById(userId, taskId)).thenReturn(Optional.of(taskRecord("CONFIRMED", 2)));

        service.confirm(userId, taskId, 1, true, null, idempotencyKey, requestId, request, role);

        String keyHash = sha256(idempotencyKey);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(quotas, times(1)).consume(eq(userId), eq(QuotaConstants.RESOURCE_DELIVERY_CONFIRM),
                keyCaptor.capture(), eq(QuotaConstants.REFERENCE_DELIVERY_TASK), eq(taskId),
                eq(QuotaConstants.REASON_DELIVERY_CONFIRM));
        // baseKey 稳定、<=110、不含明文 Idempotency-Key。
        String baseKey = keyCaptor.getValue();
        assertThat(baseKey).isEqualTo("delivery:" + taskId + ":" + keyHash);
        assertThat(baseKey.length()).isLessThanOrEqualTo(110);
        assertThat(baseKey).doesNotContain(idempotencyKey);

        // consume 必须先于 confirmTask 状态写入与事件/审计写入。
        InOrder order = inOrder(quotas, tasks, auditLogs);
        order.verify(quotas).consume(eq(userId), eq(QuotaConstants.RESOURCE_DELIVERY_CONFIRM),
                eq(baseKey), eq(QuotaConstants.REFERENCE_DELIVERY_TASK), eq(taskId),
                eq(QuotaConstants.REASON_DELIVERY_CONFIRM));
        order.verify(tasks).confirmTask(userId, taskId, 1, null);
        order.verify(tasks).insertEvent(eq(userId), eq(taskId), eq("CONFIRMED"), any(), any(),
                eq("USER"), eq(userId), eq(requestId), anyString(), anyString(), any());
        order.verify(auditLogs).append(eq(userId), eq(role), eq("DELIVERY_TASK_CONFIRMED"),
                eq("DELIVERY_TASK"), eq(taskId), eq("SUCCESS"), eq(request), any());
    }

    // ---- 3. 相同 key replay：零次新增 consume ----

    @Test
    void sameKeyReplayNeverConsumesAgain() {
        String keyHash = sha256(idempotencyKey);
        String payloadHash = sha256(userId + "|" + taskId + "|1|true|null");
        EventRow confirmedEvent = new EventRow(
                1L, "CONFIRMED", "WAITING_CONFIRM", "CONFIRMED", "USER", userId,
                Instant.now(), Map.of("payloadHash", payloadHash));
        when(tasks.findByIdForUpdate(userId, taskId))
                .thenReturn(Optional.of(taskRecord("CONFIRMED", 2)));
        when(tasks.findEvent(eq(userId), eq(taskId), eq("confirm:" + keyHash)))
                .thenReturn(Optional.of(confirmedEvent));

        service.confirm(userId, taskId, 1, true, null, idempotencyKey, requestId, request, role);

        verify(quotas, never()).consume(any(), any(), any(), any(), any(), any());
        verify(tasks, never()).confirmTask(any(), any(), anyInt(), any());
        verify(tasks, never()).insertEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(auditLogs, never()).append(any(), any(), anyString(), anyString(),
                any(), anyString(), any(), any());
    }

    // ---- 4. 额度不足：不调用 confirmTask/insertEvent/audit ----

    @Test
    void quotaExceededSkipsStateEventAndAudit() {
        when(tasks.findByIdForUpdate(userId, taskId))
                .thenReturn(Optional.of(taskRecord("WAITING_CONFIRM", 1)));
        when(tasks.findEvent(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.findEventByKeyHash(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                "本月投递确认额度已用完，请升级套餐或下月再试"))
                .when(quotas).consume(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.confirm(
                userId, taskId, 1, true, null, idempotencyKey, requestId, request, role))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.code()).isEqualTo("QUOTA_EXCEEDED");
                });

        verify(tasks, never()).confirmTask(any(), any(), anyInt(), any());
        verify(tasks, never()).insertEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(auditLogs, never()).append(any(), any(), anyString(), anyString(),
                any(), anyString(), any(), any());
    }

    // ---- 5. 非 replay 的二次确认因状态不允许也不扣 ----

    @Test
    void nonReplaySecondConfirmBlockedByStateDoesNotConsume() {
        when(tasks.findByIdForUpdate(userId, taskId))
                .thenReturn(Optional.of(taskRecord("CONFIRMED", 1)));
        when(tasks.findEvent(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.findEventByKeyHash(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(
                userId, taskId, 1, true, null, idempotencyKey, requestId, request, role))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("INVALID_STATE_TRANSITION");
                });

        verify(quotas, never()).consume(any(), any(), any(), any(), any(), any());
        verify(tasks, never()).confirmTask(any(), any(), anyInt(), any());
        verify(tasks, never()).insertEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(auditLogs, never()).append(any(), any(), anyString(), anyString(),
                any(), anyString(), any(), any());
    }

    // ---- 6. 构造器完整注入 QuotaService ----

    @Test
    void constructorWiresQuotaServiceAndFullDependencySet() {
        // 完整构造器组装（见 setUp）；这里仅验证服务可用且 consume 依赖生效。
        when(tasks.findByIdForUpdate(userId, taskId))
                .thenReturn(Optional.of(taskRecord("WAITING_CONFIRM", 1)));
        when(tasks.findEvent(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.findEventByKeyHash(eq(userId), eq(taskId), anyString())).thenReturn(Optional.empty());
        when(tasks.confirmTask(userId, taskId, 1, null)).thenReturn(true);
        when(tasks.findById(userId, taskId)).thenReturn(Optional.of(taskRecord("CONFIRMED", 2)));

        service.confirm(userId, taskId, 1, true, null, idempotencyKey, requestId, request, role);

        verify(quotas, times(1)).consume(eq(userId), eq(QuotaConstants.RESOURCE_DELIVERY_CONFIRM),
                anyString(), eq(QuotaConstants.REFERENCE_DELIVERY_TASK), eq(taskId),
                eq(QuotaConstants.REASON_DELIVERY_CONFIRM));
    }

    // ---- helpers ----

    private void swapTransactions(DeliveryService service) {
        try {
            TransactionTemplate mockTx = mock(TransactionTemplate.class);
            when(mockTx.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0,
                        org.springframework.transaction.support.TransactionCallback.class);
                return callback.doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class));
            });
            var field = DeliveryService.class.getDeclaredField("transactions");
            field.setAccessible(true);
            field.set(service, mockTx);
        } catch (Exception e) {
            throw new RuntimeException("Failed to swap DeliveryService transactions", e);
        }
    }

    private TaskRecord taskRecord(String status, int version) {
        return new TaskRecord(
                taskId, jobPostId, jobMatchId, null, status, "问候语", 1, null,
                "payload-hash", null, null, null, null, 0,
                null, null, false, null, null,
                version, Instant.now(), Instant.now());
    }

    private MatchRecord matchRecord(String status, String decision) {
        return new MatchRecord(
                jobMatchId, userId, jobPostId, UUID.randomUUID(), UUID.randomUUID(),
                status, null, decision, null, List.of(), List.of(), null,
                null, null, null, null, null, null, null, null, 0,
                Instant.now(), null, null);
    }

    private JobModels.JobDetail jobDetail() {
        return new JobModels.JobDetail(
                jobPostId, "BOSS", "ext-1", "Java工程师", "示例公司", null, "上海",
                null, null, null, "https://www.zhipin.com/job_detail/test.html",
                Map.of(), List.of(), List.of(), "ACTIVE",
                Instant.now(), Instant.now(), null, null);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
