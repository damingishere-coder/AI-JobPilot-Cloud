package com.getjobs.cloud.quota;

import java.time.Instant;
import java.util.List;

/**
 * 额度领域与 API 模型。
 *
 * <p>对外视图只暴露当前用户自己的 plan/reset 信息与两类资源的
 * total/used/reserved/remaining，绝不返回流水幂等键、其他用户或敏感字段。</p>
 */
public final class QuotaModels {

    private QuotaModels() {
    }

    /** 单行额度快照（user_quotas 行）。 */
    public record QuotaRow(
            java.util.UUID id,
            java.util.UUID userId,
            String planCode,
            String resourceCode,
            Instant periodStart,
            Instant periodEnd,
            long limitAmount,
            long usedAmount,
            long reservedAmount,
            long version
    ) {
    }

    /** 一次额度操作的结果，供调用方在后续 commit/release 时复核。 */
    public record QuotaReservation(
            java.util.UUID quotaId,
            java.util.UUID reservationId,
            long reservedAfter
    ) {
    }

    /** 已存在的流水操作（幂等 replay 时用于返回既有结果，不产生新变更）。 */
    public record QuotaLogOperation(
            java.util.UUID quotaId,
            java.util.UUID reservationId,
            long amount,
            String action,
            long balanceAfter
    ) {
    }

    /** 单资源对外视图。 */
    public record QuotaResourceView(
            String resourceCode,
            long total,
            long used,
            long reserved,
            long remaining
    ) {
    }

    /** GET /api/quota/me 的返回体。 */
    public record QuotaMeView(
            String plan,
            String resetCycle,
            Instant resetAt,
            List<QuotaResourceView> resources
    ) {
    }
}
