package com.getjobs.cloud.auth;

import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.web.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

/**
 * 认证入口限流：按 IP + 规范邮箱哈希维度计数，键值经 HMAC 打散。
 * 计数与窗口逻辑委托给共享的 {@link ApiRateLimiter}，保证与 AI 匹配、
 * 简历上传等保护性限流使用同一套集群一致实现。
 */
@Component
@Profile("api")
public class AuthRateLimiter {
    private static final String KEY_PREFIX = "ai-jobpilot:auth:rate:";

    private final ApiRateLimiter limiter;
    private final SecurityFingerprintService fingerprints;
    private final AuthProperties properties;

    public AuthRateLimiter(
            ApiRateLimiter limiter,
            SecurityFingerprintService fingerprints,
            AuthProperties properties
    ) {
        this.limiter = limiter;
        this.fingerprints = fingerprints;
        this.properties = properties;
    }

    public void checkLogin(String remoteAddress, String normalizedEmail) {
        check("login:ip:" + fingerprints.hash(remoteAddress), properties.getLoginIpLimit(), properties.getLoginIpWindow());
        check("login:email:" + fingerprints.hash(normalizedEmail), properties.getLoginEmailLimit(), properties.getLoginEmailWindow());
    }

    public void checkRegister(String remoteAddress, String normalizedEmail) {
        check("register:ip:" + fingerprints.hash(remoteAddress), properties.getRegisterIpLimit(), properties.getRegisterIpWindow());
        check("register:email:" + fingerprints.hash(normalizedEmail), 3, Duration.ofDays(1));
    }

    public void checkCsrf(String remoteAddress) {
        check("csrf:ip:" + fingerprints.hash(remoteAddress), properties.getCsrfIpLimit(), properties.getCsrfIpWindow());
    }

    private void check(String suffix, int limit, Duration window) {
        long current;
        try {
            current = limiter.increment(KEY_PREFIX + suffix, window);
        } catch (DataAccessException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DEPENDENCY_UNAVAILABLE",
                    "认证服务暂不可用，请稍后再试",
                    true,
                    5,
                    List.of()
            );
        }
        if (current > limit) {
            throw ApiRateLimiter.rateLimited(window);
        }
    }
}
