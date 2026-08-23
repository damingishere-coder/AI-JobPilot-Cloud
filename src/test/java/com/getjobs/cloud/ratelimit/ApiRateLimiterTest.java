package com.getjobs.cloud.ratelimit;

import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ApiRateLimiter limiter = new ApiRateLimiter(redis);

    @Test
    void passesWhenCountStaysWithinLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(3L);

        limiter.check("ai-jobpilot:api:rate:test:user:abc", 5, Duration.ofMinutes(1));

        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void throwsRateLimitedWithRetryAfterWhenCountExceedsLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(11L);

        assertThatThrownBy(() -> limiter.check(
                "ai-jobpilot:api:rate:test:user:abc", 10, Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(429);
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.retryAfterSeconds()).isEqualTo(60);
                    assertThat(exception.getMessage()).doesNotContain("Redis", "INCR", "PEXPIRE");
                });
    }

    @Test
    void failsClosedWithoutRedisDetailsWhenRedisIsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("connection refused 127.0.0.1:6379"));

        assertThatThrownBy(() -> limiter.check(
                "ai-jobpilot:api:rate:test:user:abc", 10, Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("6379", "connection");
                });
    }

    @Test
    void usesTheExactKeyAndWindowMillisInTheRedisScript() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        limiter.check("ai-jobpilot:api:rate:test:user:abc", 10, Duration.ofSeconds(90));

        var windowCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("ai-jobpilot:api:rate:test:user:abc")),
                windowCaptor.capture()
        );
        assertThat(windowCaptor.getValue()).containsExactly("90000");
    }

    @Test
    void rawDataAccessExceptionsAreSurfacedByIncrementForCallerSpecificHandling() {
        DataAccessException failure = new RedisConnectionFailureException("down");
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(failure);

        assertThatThrownBy(() -> limiter.increment("key", Duration.ofSeconds(1)))
                .isSameAs(failure);
    }
}
