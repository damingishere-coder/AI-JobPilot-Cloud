package com.getjobs.cloud.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.quota.QuotaModels.QuotaLogOperation;
import com.getjobs.cloud.quota.QuotaModels.QuotaRow;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * user_quotas 与 quota_usage_logs 的数据访问。
 *
 * <p>所有方法必须在调用方已开启的事务及 RLS tenant context 中执行；行级锁
 * 由 {@link #lockCurrent} 的 SELECT ... FOR UPDATE 提供。</p>
 */
@Repository
@Profile({"api", "worker"})
public class QuotaRepository {

    private static final String QUOTA_COLUMNS = """
            id, user_id, plan_code, resource_code, period_start, period_end,
            limit_amount, used_amount, reserved_amount, version
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public QuotaRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 幂等初始化某资源当前周期 FREE 额度行。 */
    public void initializeFree(
            UUID userId,
            String planCode,
            String resourceCode,
            Instant periodStart,
            Instant periodEnd,
            long limitAmount
    ) {
        jdbc.update(
                """
                INSERT INTO app.user_quotas (
                    user_id, plan_code, resource_code, period_start, period_end, limit_amount
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, resource_code, period_start, period_end) DO NOTHING
                """,
                userId,
                planCode,
                resourceCode,
                Timestamp.from(periodStart),
                Timestamp.from(periodEnd),
                limitAmount
        );
    }

    /** 加锁读取当前周期额度行；无行返回空。 */
    public Optional<QuotaRow> lockCurrent(UUID userId, String resourceCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT %s FROM app.user_quotas
                    WHERE user_id = ? AND resource_code = ?
                      AND period_start <= now() AND now() < period_end
                    FOR UPDATE
                    """.formatted(QUOTA_COLUMNS),
                    this::mapQuotaRow,
                    userId,
                    resourceCode
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** 按主键锁定预占所属额度行；跨月结算必须回到原额度周期，不能误扣新周期。 */
    public Optional<QuotaRow> lockById(UUID userId, UUID quotaId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT %s FROM app.user_quotas WHERE user_id = ? AND id = ? FOR UPDATE"
                            .formatted(QUOTA_COLUMNS),
                    this::mapQuotaRow,
                    userId,
                    quotaId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** 读取当前用户所有资源在当前周期的额度行（只读查询）。 */
    public List<QuotaRow> findCurrentPeriod(UUID userId) {
        return jdbc.query(
                """
                SELECT %s FROM app.user_quotas
                WHERE user_id = ? AND period_start <= now() AND now() < period_end
                ORDER BY resource_code
                """.formatted(QUOTA_COLUMNS),
                this::mapQuotaRow,
                userId
        );
    }

    /** 预占：reserved_amount += amount。 */
    public void reserve(UUID quotaId, UUID userId, long amount) {
        jdbc.update(
                """
                UPDATE app.user_quotas
                SET reserved_amount = reserved_amount + ?, version = version + 1, updated_at = now()
                WHERE id = ? AND user_id = ?
                """,
                amount,
                quotaId,
                userId
        );
    }

    /** 确认结算：used_amount += amount 且 reserved_amount -= amount。 */
    public void commit(UUID quotaId, UUID userId, long amount) {
        jdbc.update(
                """
                UPDATE app.user_quotas
                SET used_amount = used_amount + ?,
                    reserved_amount = reserved_amount - ?,
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND user_id = ?
                """,
                amount,
                amount,
                quotaId,
                userId
        );
    }

    /** 释放预占：reserved_amount -= amount。 */
    public void release(UUID quotaId, UUID userId, long amount) {
        jdbc.update(
                """
                UPDATE app.user_quotas
                SET reserved_amount = reserved_amount - ?, version = version + 1, updated_at = now()
                WHERE id = ? AND user_id = ?
                """,
                amount,
                quotaId,
                userId
        );
    }

    /** 直接消耗（投递确认）：used_amount += amount。 */
    public void consume(UUID quotaId, UUID userId, long amount) {
        jdbc.update(
                """
                UPDATE app.user_quotas
                SET used_amount = used_amount + ?, version = version + 1, updated_at = now()
                WHERE id = ? AND user_id = ?
                """,
                amount,
                quotaId,
                userId
        );
    }

    /** 判断该幂等键是否已产生真实流水（幂等 replay 依据）。 */
    public boolean logExists(UUID userId, String operationKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM app.quota_usage_logs
                    WHERE user_id = ? AND operation_key = ?
                )
                """,
                Boolean.class,
                userId,
                operationKey
        ));
    }

    /** 读取既有流水用于幂等 replay；无记录返回空。 */
    public Optional<QuotaLogOperation> findOperation(UUID userId, String operationKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT quota_id, reservation_id, amount, action, balance_after
                    FROM app.quota_usage_logs
                    WHERE user_id = ? AND operation_key = ?
                    """,
                    (resultSet, rowNumber) -> new QuotaLogOperation(
                            resultSet.getObject("quota_id", UUID.class),
                            resultSet.getObject("reservation_id", UUID.class),
                            resultSet.getLong("amount"),
                            resultSet.getString("action"),
                            resultSet.getLong("balance_after")
                    ),
                    userId,
                    operationKey
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** 写入一条不可变额度流水。 */
    public void appendLog(
            UUID userId,
            UUID quotaId,
            String resourceCode,
            String action,
            long amount,
            String referenceType,
            UUID referenceId,
            String operationKey,
            UUID reservationId,
            String reason,
            long balanceAfter,
            Map<String, Object> metadata
    ) {
        jdbc.update(
                """
                INSERT INTO app.quota_usage_logs (
                    user_id, quota_id, resource_code, action, amount, reference_type,
                    reference_id, operation_key, reservation_id, reason, balance_after, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                userId,
                quotaId,
                resourceCode,
                action,
                amount,
                referenceType,
                referenceId,
                operationKey,
                reservationId,
                reason,
                balanceAfter,
                toJson(metadata)
        );
    }

    private QuotaRow mapQuotaRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new QuotaRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("plan_code"),
                resultSet.getString("resource_code"),
                resultSet.getTimestamp("period_start").toInstant(),
                resultSet.getTimestamp("period_end").toInstant(),
                resultSet.getLong("limit_amount"),
                resultSet.getLong("used_amount"),
                resultSet.getLong("reserved_amount"),
                resultSet.getLong("version")
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化额度流水 metadata", exception);
        }
    }
}
