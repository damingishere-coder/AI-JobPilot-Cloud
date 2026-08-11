package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

@Component
@Profile("api")
public class AuthRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecurityFingerprintService fingerprints;
    private final AuthProperties properties;

    public AuthRateLimiter(
            StringRedisTemplate redis,
            SecurityFingerprintService fingerprints,
            AuthProperties properties
    ) {
        this.redis = redis;
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
        try {
            Long value = redis.execute(
                    INCREMENT_SCRIPT,
                    List.of("ai-jobpilot:auth:rate:" + suffix),
                    Long.toString(window.toMillis())
            );
            if (value != null && value > limit) {
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED",
                        "请求过于频繁，请稍后再试",
                        true,
                        window.toSeconds(),
                        List.of()
                );
            }
        } catch (ApiException exception) {
            throw exception;
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
    }
}
