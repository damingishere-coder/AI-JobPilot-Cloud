package com.getjobs.cloud.admin;

import com.getjobs.cloud.admin.AdminModels.AuditLogView;
import com.getjobs.cloud.admin.AdminModels.DashboardView;
import com.getjobs.cloud.admin.AdminModels.DeliveryFailureView;
import com.getjobs.cloud.admin.AdminModels.QuotaAdjustOutcome;
import com.getjobs.cloud.admin.AdminModels.ResourceQuotaView;
import com.getjobs.cloud.admin.AdminModels.UserAdminView;
import com.getjobs.cloud.admin.AdminModels.UserPage;
import com.getjobs.cloud.admin.AdminModels.UserQuotaRowView;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台窄 SECURITY DEFINER 函数的数据访问。
 *
 * <p>所有查询都把当前管理员 actorId 作为函数第一个参数传入；邮箱脱敏、跨用户
 * 聚合与 RLS 绕过都只发生在数据库函数内部。数据库拒绝（如 actor 不是 ACTIVE
 * ADMIN、目标用户不存在）会转换为可识别的 API 错误。</p>
 */
@Repository
@Profile("api")
public class AdminRepository {

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserPage listUsers(UUID actorId, long page, long size) {
        try {
            List<UserAdminView> users = jdbc.query(
                    "SELECT * FROM app.admin_list_users(?, ?, ?)",
                    this::mapUserAdminView,
                    actorId,
                    page,
                    size
            );
            long total = users.isEmpty() ? 0 : users.get(0).totalCount();
            return new UserPage(total, users);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public Optional<UserAdminView> findUserDetail(UUID actorId, UUID userId) {
        try {
            return jdbc.query(
                    "SELECT * FROM app.admin_get_user_detail(?, ?)",
                    this::mapUserAdminView,
                    actorId,
                    userId
            ).stream().findFirst();
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public List<UserQuotaRowView> findUserQuotaRows(UUID actorId, UUID userId) {
        try {
            return jdbc.query(
                    "SELECT * FROM app.admin_get_user_quota_rows(?, ?)",
                    this::mapQuotaRowView,
                    actorId,
                    userId
            );
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public DashboardView dashboard(UUID actorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM app.admin_dashboard(?)",
                    this::mapDashboard,
                    actorId
            );
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public List<AuditLogView> listAuditLogs(UUID actorId, long limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM app.admin_list_audit_logs(?, ?)",
                    this::mapAuditLogView,
                    actorId,
                    limit
            );
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public List<DeliveryFailureView> listDeliveryFailures(UUID actorId, long limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM app.admin_list_delivery_failures(?, ?)",
                    this::mapDeliveryFailureView,
                    actorId,
                    limit
            );
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    public QuotaAdjustOutcome adjustQuota(
            UUID actorId,
            UUID targetId,
            String plan,
            long analysisTotal,
            long deliveryTotal,
            String reason,
            String operationKey
    ) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT * FROM app.admin_set_quota(
                        ?, ?, CAST(? AS varchar), ?, ?, CAST(? AS varchar), CAST(? AS varchar)
                    )
                    """,
                    this::mapAdjustOutcome,
                    actorId,
                    targetId,
                    plan,
                    analysisTotal,
                    deliveryTotal,
                    reason,
                    operationKey
            );
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private UserAdminView mapUserAdminView(ResultSet resultSet, int rowNumber) throws SQLException {
        long totalCount = resultSet.getLong("total_count");
        return new UserAdminView(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("email_masked"),
                resultSet.getString("role"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("plan_code"),
                new ResourceQuotaView(
                        "AI_ANALYSIS",
                        resultSet.getLong("analysis_total"),
                        resultSet.getLong("analysis_used"),
                        resultSet.getLong("analysis_reserved"),
                        resultSet.getLong("analysis_remaining")
                ),
                new ResourceQuotaView(
                        "DELIVERY_CONFIRM",
                        resultSet.getLong("delivery_total"),
                        resultSet.getLong("delivery_used"),
                        resultSet.getLong("delivery_reserved"),
                        resultSet.getLong("delivery_remaining")
                ),
                resultSet.getLong("job_count"),
                resultSet.getLong("match_count"),
                resultSet.getLong("delivery_count"),
                resultSet.getLong("success_count"),
                resultSet.getLong("failed_count"),
                resultSet.getLong("active_device_count"),
                totalCount
        );
    }

    private UserQuotaRowView mapQuotaRowView(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserQuotaRowView(
                resultSet.getObject("quota_id", UUID.class),
                resultSet.getString("plan_code"),
                resultSet.getString("resource_code"),
                resultSet.getLong("total"),
                resultSet.getLong("used"),
                resultSet.getLong("reserved"),
                resultSet.getLong("remaining"),
                resultSet.getTimestamp("reset_at").toInstant()
        );
    }

    private DashboardView mapDashboard(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DashboardView(
                resultSet.getLong("total_users"),
                resultSet.getLong("active_users"),
                resultSet.getLong("jobs"),
                resultSet.getLong("ai_analyses"),
                resultSet.getLong("delivery_tasks"),
                resultSet.getLong("success_count"),
                resultSet.getLong("failed_count"),
                resultSet.getLong("active_devices"),
                resultSet.getLong("recent_failures")
        );
    }

    private AuditLogView mapAuditLogView(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditLogView(
                resultSet.getLong("id"),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("user_email_masked"),
                resultSet.getString("actor_type"),
                resultSet.getString("action"),
                resultSet.getString("target_type"),
                resultSet.getObject("target_id", UUID.class),
                resultSet.getString("result"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private DeliveryFailureView mapDeliveryFailureView(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeliveryFailureView(
                resultSet.getObject("task_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("email_masked"),
                resultSet.getString("platform"),
                resultSet.getString("status"),
                resultSet.getString("last_error_code"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private QuotaAdjustOutcome mapAdjustOutcome(ResultSet resultSet, int rowNumber) throws SQLException {
        return new QuotaAdjustOutcome(
                resultSet.getString("outcome"),
                resultSet.getBoolean("applied"),
                resultSet.getString("plan_code"),
                resultSet.getLong("analysis_old_total"),
                resultSet.getLong("analysis_total"),
                resultSet.getLong("analysis_used"),
                resultSet.getLong("analysis_reserved"),
                resultSet.getLong("analysis_remaining"),
                resultSet.getLong("delivery_old_total"),
                resultSet.getLong("delivery_total"),
                resultSet.getLong("delivery_used"),
                resultSet.getLong("delivery_reserved"),
                resultSet.getLong("delivery_remaining")
        );
    }

    private ApiException translate(DataAccessException exception) {
        String message = rootMessage(exception);
        if (message != null && message.contains("ADMIN_TARGET_NOT_FOUND")) {
            return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "目标用户不存在");
        }
        if (message != null && message.contains("ADMIN_")) {
            return new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_OPERATION_REJECTED", "管理操作参数不正确");
        }
        throw exception;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
