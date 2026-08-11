package com.getjobs.cloud.process;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("migrate")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MigrationCompletionRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        log.info("PostgreSQL Flyway 迁移已完成，迁移进程正常退出");
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
