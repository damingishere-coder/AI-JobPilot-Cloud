package com.getjobs.cloud.quota;

import com.getjobs.cloud.quota.QuotaModels.QuotaLogOperation;
import com.getjobs.cloud.quota.QuotaModels.QuotaMeView;
import com.getjobs.cloud.quota.QuotaModels.QuotaReservation;
import com.getjobs.cloud.quota.QuotaModels.QuotaResourceView;
import com.getjobs.cloud.quota.QuotaModels.QuotaRow;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.getjobs.cloud.quota.QuotaConstants.ACTION_COMMIT;
import static com.getjobs.cloud.quota.QuotaConstants.ACTION_RELEASE;
import static com.getjobs.cloud.quota.QuotaConstants.ACTION_RESERVE;
import static com.getjobs.cloud.quota.QuotaConstants.PLAN_FREE;
import static com.getjobs.cloud.quota.QuotaConstants.RESET_CYCLE_MONTHLY;
import static com.getjobs.cloud.quota.QuotaConstants.RESOURCE_AI_ANALYSIS;
import static com.getjobs.cloud.quota.QuotaConstants.RESOURCE_DELIVERY_CONFIRM;

/**
 * 额度领域服务：初始化、预占、确认、释放、直接消耗与当前用户视图。
 *
 * <p>所有变更方法都在调用方事务（未开启则新开一个）及同一 RLS tenant context
 * 内执行，并通过当前周期行的 SELECT ... FOR UPDATE 串行化同一用户/资源的并发
 * 操作。幂等键（operationKey）重复调用只返回既有结果，绝不再次更新额度或新增
 * 流水。</p>
 */
@Service
@Profile({"api", "worker"})
public class QuotaService {

    private static final List<String> RESOURCE_CODES = List.of(
            RESOURCE_AI_ANALYSIS, RESOURCE_DELIVERY_CONFIRM
    );

    private final QuotaRepository quotas;
    private final QuotaProperties properties;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public QuotaService(
            QuotaRepository quotas,
            QuotaProperties properties,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.quotas = quotas;
        this.properties = properties;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 幂等初始化当前用户当前 UTC 自然月的两行 FREE 额度（AI_ANALYSIS / DELIVERY_CONFIRM）。
     * 供注册事务调用；重复调用不产生重复行。
     */
    public void initializeFree(UUID userId) {
        transactions.execute(status -> tenants.execute(userId, () -> {
            MonthlyPeriod period = currentMonth();
            quotas.initializeFree(
                    userId, PLAN_FREE, RESOURCE_AI_ANALYSIS,
                    period.start(), period.end(), properties.analysisLimit()
            );
            quotas.initializeFree(
                    userId, PLAN_FREE, RESOURCE_DELIVERY_CONFIRM,
                    period.start(), period.end(), properties.deliveryLimit()
            );
            return null;
        }));
    }

    /** 预占 1 次额度（如 AI 分析入队时）。 */
    public QuotaReservation reserve(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason
    ) {
        return reserve(userId, resourceCode, operationKey, referenceType, referenceId, reason, 1);
    }

    /** 预占指定次数额度；幂等：同一 operationKey 重复调用只返回既有结果。 */
    public QuotaReservation reserve(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason,
            int amount
    ) {
        return transactions.execute(status -> tenants.execute(userId, () -> {
            validateOperation(resourceCode, operationKey, referenceType, amount);
            QuotaRow row = lockOrInitialize(userId, resourceCode);
            String reserveKey = actionKey(operationKey, ACTION_RESERVE);
            Optional<QuotaLogOperation> existing = quotas.findOperation(userId, reserveKey);
            if (existing.isPresent()) {
                return replayReservation(existing.get());
            }
            long remaining = row.limitAmount() - row.usedAmount() - row.reservedAmount();
            if (remaining < amount) {
                throw quotaExceeded(resourceCode);
            }
            quotas.reserve(row.id(), userId, amount);
            UUID reservationId = UUID.randomUUID();
            quotas.appendLog(
                    userId, row.id(), resourceCode, ACTION_RESERVE, amount,
                    referenceType, referenceId, reserveKey, reservationId,
                    normalizeReason(reason), row.usedAmount(), Map.of()
            );
            return new QuotaReservation(row.id(), reservationId, row.reservedAmount() + amount);
        }));
    }

    /** 确认预占：reserved-1 且 used+1（如 AI 分析成功）。幂等。 */
    public void commitReservation(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason
    ) {
        commitReservation(userId, resourceCode, operationKey, referenceType, referenceId, reason, 1);
    }

    /** 确认指定次数的预占；幂等 replay 不更新额度、不新增流水。 */
    public void commitReservation(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason,
            int amount
    ) {
        transactions.execute(status -> tenants.execute(userId, () -> {
            validateOperation(resourceCode, operationKey, referenceType, amount);
            QuotaLogOperation reservation = requireReservation(userId, operationKey, amount);
            QuotaRow row = lockReservationQuota(userId, reservation, resourceCode);
            String commitKey = actionKey(operationKey, ACTION_COMMIT);
            String releaseKey = actionKey(operationKey, ACTION_RELEASE);
            if (quotas.logExists(userId, commitKey)) return null;
            if (quotas.logExists(userId, releaseKey)) throw reservationInvalid();
            if (row.reservedAmount() < amount) {
                throw reservationInvalid();
            }
            quotas.commit(row.id(), userId, amount);
            quotas.appendLog(
                    userId, row.id(), resourceCode, ACTION_COMMIT, amount,
                    referenceType, referenceId, commitKey, reservation.reservationId(),
                    normalizeReason(reason), row.usedAmount() + amount, Map.of()
            );
            return null;
        }));
    }

    /** 释放预占：reserved-1（如 AI 分析最终失败）。幂等。 */
    public void releaseReservation(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason
    ) {
        releaseReservation(userId, resourceCode, operationKey, referenceType, referenceId, reason, 1);
    }

    /** 释放指定次数的预占；幂等 replay 不更新额度、不新增流水。 */
    public void releaseReservation(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason,
            int amount
    ) {
        transactions.execute(status -> tenants.execute(userId, () -> {
            validateOperation(resourceCode, operationKey, referenceType, amount);
            QuotaLogOperation reservation = requireReservation(userId, operationKey, amount);
            QuotaRow row = lockReservationQuota(userId, reservation, resourceCode);
            String commitKey = actionKey(operationKey, ACTION_COMMIT);
            String releaseKey = actionKey(operationKey, ACTION_RELEASE);
            if (quotas.logExists(userId, releaseKey)) return null;
            if (quotas.logExists(userId, commitKey)) throw reservationInvalid();
            if (row.reservedAmount() < amount) {
                throw reservationInvalid();
            }
            quotas.release(row.id(), userId, amount);
            quotas.appendLog(
                    userId, row.id(), resourceCode, ACTION_RELEASE, amount,
                    referenceType, referenceId, releaseKey, reservation.reservationId(),
                    normalizeReason(reason), row.usedAmount(), Map.of()
            );
            return null;
        }));
    }

    /** 直接消耗 1 次额度（投递确认时 used+1，执行失败不返还）。幂等。 */
    public void consume(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason
    ) {
        consume(userId, resourceCode, operationKey, referenceType, referenceId, reason, 1);
    }

    /** 直接消耗指定次数额度；幂等 replay 不更新额度、不新增流水。 */
    public void consume(
            UUID userId,
            String resourceCode,
            String operationKey,
            String referenceType,
            UUID referenceId,
            String reason,
            int amount
    ) {
        transactions.execute(status -> tenants.execute(userId, () -> {
            validateOperation(resourceCode, operationKey, referenceType, amount);
            QuotaRow row = lockOrInitialize(userId, resourceCode);
            String consumeKey = actionKey(operationKey, ACTION_COMMIT);
            if (quotas.logExists(userId, consumeKey)) {
                return null;
            }
            long remaining = row.limitAmount() - row.usedAmount() - row.reservedAmount();
            if (remaining < amount) {
                throw quotaExceeded(resourceCode);
            }
            quotas.consume(row.id(), userId, amount);
            quotas.appendLog(
                    userId, row.id(), resourceCode, ACTION_COMMIT, amount,
                    referenceType, referenceId, consumeKey, null,
                    normalizeReason(reason), row.usedAmount() + amount, Map.of()
            );
            return null;
        }));
    }

    /** 当前用户当月额度视图（仅本人数据）。 */
    public QuotaMeView currentView(UUID userId) {
        return transactions.execute(status -> tenants.execute(userId, () -> {
            lockOrInitialize(userId, RESOURCE_AI_ANALYSIS);
            lockOrInitialize(userId, RESOURCE_DELIVERY_CONFIRM);
            List<QuotaRow> rows = quotas.findCurrentPeriod(userId);
            Map<String, QuotaRow> byResource = rows.stream().collect(
                    java.util.stream.Collectors.toMap(QuotaRow::resourceCode, row -> row, (a, b) -> a)
            );
            String plan = rows.isEmpty() ? PLAN_FREE : rows.get(0).planCode();
            Instant resetAt = rows.stream()
                    .map(QuotaRow::periodEnd)
                    .max(java.util.Comparator.naturalOrder())
                    .orElseGet(() -> currentMonth().end());
            return new QuotaMeView(
                    plan,
                    RESET_CYCLE_MONTHLY,
                    resetAt,
                    List.of(
                            resourceView(RESOURCE_AI_ANALYSIS, byResource.get(RESOURCE_AI_ANALYSIS)),
                            resourceView(RESOURCE_DELIVERY_CONFIRM, byResource.get(RESOURCE_DELIVERY_CONFIRM))
                    )
            );
        }));
    }

    private QuotaResourceView resourceView(String resourceCode, QuotaRow row) {
        long total = row == null ? 0 : row.limitAmount();
        long used = row == null ? 0 : row.usedAmount();
        long reserved = row == null ? 0 : row.reservedAmount();
        return new QuotaResourceView(
                resourceCode,
                total,
                used,
                reserved,
                Math.max(0, total - used - reserved)
        );
    }

    /** 幂等：锁定当前周期行，缺失则先幂等初始化再重新加锁。 */
    private QuotaRow lockOrInitialize(UUID userId, String resourceCode) {
        Optional<QuotaRow> row = quotas.lockCurrent(userId, resourceCode);
        if (row.isPresent()) {
            return row.get();
        }
        MonthlyPeriod period = currentMonth();
        long limit = RESOURCE_AI_ANALYSIS.equals(resourceCode)
                ? properties.analysisLimit()
                : properties.deliveryLimit();
        quotas.initializeFree(userId, PLAN_FREE, resourceCode, period.start(), period.end(), limit);
        return quotas.lockCurrent(userId, resourceCode)
                .orElseThrow(() -> new IllegalStateException("额度初始化后仍无法锁定当前周期行"));
    }

    private QuotaReservation replayReservation(QuotaLogOperation operation) {
        return new QuotaReservation(operation.quotaId(), operation.reservationId(), 0);
    }

    private QuotaLogOperation requireReservation(UUID userId, String operationKey, int amount) {
        QuotaLogOperation reservation = quotas.findOperation(userId, actionKey(operationKey, ACTION_RESERVE))
                .orElseThrow(this::reservationInvalid);
        if (!ACTION_RESERVE.equals(reservation.action())
                || reservation.reservationId() == null
                || reservation.amount() != amount) {
            throw reservationInvalid();
        }
        return reservation;
    }

    private QuotaRow lockReservationQuota(
            UUID userId,
            QuotaLogOperation reservation,
            String resourceCode
    ) {
        QuotaRow row = quotas.lockById(userId, reservation.quotaId())
                .orElseThrow(this::reservationInvalid);
        if (!resourceCode.equals(row.resourceCode())) {
            throw reservationInvalid();
        }
        return row;
    }

    private void validateOperation(String resourceCode, String operationKey, String referenceType, int amount) {
        if (!RESOURCE_CODES.contains(resourceCode)) {
            throw new IllegalArgumentException("不支持的额度资源: " + resourceCode);
        }
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 110) {
            throw new IllegalArgumentException("额度操作幂等键必须为 1-110 个字符");
        }
        if (referenceType == null || referenceType.isBlank()) {
            throw new IllegalArgumentException("额度操作引用类型不能为空");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("额度操作数量必须为正整数");
        }
    }

    private String actionKey(String operationKey, String action) {
        String key = operationKey + ":" + action;
        if (key.length() > 120) {
            throw new IllegalArgumentException("额度操作幂等键过长");
        }
        return key;
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null || reason.isBlank() ? "额度操作" : reason.trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    private ApiException quotaExceeded(String resourceCode) {
        String message = RESOURCE_AI_ANALYSIS.equals(resourceCode)
                ? "本月 AI 分析额度已用完，请升级套餐或下月再试"
                : "本月投递确认额度已用完，请升级套餐或下月再试";
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", message);
    }

    private ApiException reservationInvalid() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "QUOTA_RESERVATION_INVALID",
                "该预占不存在或已被结算，请核对操作幂等键"
        );
    }

    private MonthlyPeriod currentMonth() {
        LocalDate today = LocalDate.now(clock);
        LocalDate firstDay = today.withDayOfMonth(1);
        Instant start = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = firstDay.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new MonthlyPeriod(start, end);
    }

    private record MonthlyPeriod(Instant start, Instant end) {
    }
}
