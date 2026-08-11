package com.getjobs.cloud.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在已经开启的数据库事务中设置 RLS 用户上下文。
 * set_config(..., true) 的作用域仅限当前事务，连接归还连接池后不会保留用户身份。
 */
@Component
@Profile("api")
public class UserTransactionExecutor {
    private final UserRepository users;

    public UserTransactionExecutor(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID userId, Supplier<T> work) {
        users.setTenantContext(userId);
        return work.get();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(UUID userId, Runnable work) {
        users.setTenantContext(userId);
        work.run();
    }
}
