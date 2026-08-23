package com.getjobs.cloud.quota;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/**
 * 仅供 worker profile 使用的 UTC Clock。
 *
 * <p>api profile 的 Clock 由 {@code ApiSecurityConfiguration.authClock} 提供；
 * 此处仅对 worker 生效，避免与 api bean 冲突，同时满足 QuotaService 的依赖。</p>
 */
@Configuration
@Profile("worker")
public class QuotaClockConfiguration {

    @Bean
    Clock quotaClock() {
        return Clock.systemUTC();
    }
}
