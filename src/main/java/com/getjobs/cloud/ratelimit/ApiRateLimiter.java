package com.getjobs.cloud.ratelimit;

import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 可复用的 Redis 滑动计数限流组件（集群一致，无 JVM 内存状态）。
 *
 * <p>认证（登录/注册/CSRF）、AI 匹配、简历上传等安全与保护性接口共用同一套
 * Lua INCR+PEXPIRE 实现。限流超限统一返回 {@code 429 RATE_LIMITED} 并携带
 * {@code Retry-After}；Redis 不可用时按默认安全（fail-closed）返回
 * {@code 503 DEPENDENCY_UNAVAILABLE}，不泄露 Redis 内部细节。</p>
 */
@Component
@Profile("api")
public class ApiRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;

    public ApiRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 原子递增并返回当前窗口计数；Redis 不可用时抛出 {@link DataAccessException}。
     *
     * @param key    完整的 Redis key（调用方负责拼接规范维度：userId/IP+邮箱哈希等）
     * @param window 滑动窗口长度
     */
    public long increment(String key, Duration window) {
        Long value = redis.execute(
                INCREMENT_SCRIPT,
                List.of(key),
                Long.toString(window.toMillis())
        );
        return value == null ? 0 : value;
    }

    /**
     * 限流检查：超过 {@code limit} 抛出 429 RATE_LIMITED（带 Retry-After）；
     * Redis 异常按 fail-closed 抛出 503 DEPENDENCY_UNAVAILABLE（可重试）。
     */
    public void check(String key, int limit, Duration window) {
        long current;
        try {
            current = increment(key, window);
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DEPENDENCY_UNAVAILABLE",
                    "安全校验服务暂不可用，请稍后再试",
                    true,
                    5,
                    List.of()
            );
        }
        if (current > limit) {
            throw rateLimited(window);
        }
    }

    public static ApiException rateLimited(Duration window) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "请求过于频繁，请稍后再试",
                true,
                Math.max(1, window.toSeconds()),
                List.of()
        );
    }
}
