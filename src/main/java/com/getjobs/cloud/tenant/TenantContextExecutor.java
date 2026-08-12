package com.getjobs.cloud.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Establishes the PostgreSQL RLS tenant only for the current transaction.
 */
@Component
@Profile({"api", "worker"})
public class TenantContextExecutor {
    private final JdbcTemplate jdbc;

    public TenantContextExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID userId, Supplier<T> work) {
        setCurrentUser(userId);
        return work.get();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(UUID userId, Runnable work) {
        setCurrentUser(userId);
        work.run();
    }

    private void setCurrentUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("RLS 用户 ID 不能为空");
        }
        jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String.class,
                userId.toString()
        );
    }
}
