package com.getjobs.cloud.auth;

import com.getjobs.cloud.tenant.TenantContextExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在已经开启的数据库事务中设置 RLS 用户上下文。
 * set_config(..., true) 的作用域仅限当前事务，连接归还连接池后不会保留用户身份。
 */
@Component
@Profile("api")
public class UserTransactionExecutor {
    private final TenantContextExecutor tenants;

    public UserTransactionExecutor(TenantContextExecutor tenants) {
        this.tenants = tenants;
    }

    public <T> T execute(UUID userId, Supplier<T> work) {
        return tenants.execute(userId, work);
    }

    public void execute(UUID userId, Runnable work) {
        tenants.execute(userId, work);
    }
}
