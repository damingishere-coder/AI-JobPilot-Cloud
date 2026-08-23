package com.getjobs.cloud.auth;

import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
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

class AuthRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthProperties properties = new AuthProperties();
    private final SecurityFingerprintService fingerprints =
            new SecurityFingerprintService(authPropertiesWithPepper());
    private final ApiRateLimiter limiter = new ApiRateLimiter(redis);
    private final AuthRateLimiter rateLimiter = new AuthRateLimiter(limiter, fingerprints, properties);

    private static AuthProperties authPropertiesWithPepper() {
        AuthProperties properties = new AuthProperties();
        properties.setHashPepper("unit-test-auth-pepper-at-least-32-bytes");
        return properties;
    }

    @Test
    void loginKeysAreHashedPerIpAndEmailAndDelegatedToSharedLimiter() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        rateLimiter.checkLogin("127.0.0.1", "user@example.com");

        var keysCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keysCaptor.capture(), any(Object[].class));
        List<String> keys = keysCaptor.getAllValues().stream()
                .flatMap(list -> ((List<String>) list).stream())
                .toList();
        assertThat(keys).hasSize(2)
                .allMatch(key -> key.startsWith("ai-jobpilot:auth:rate:login:"))
                .allMatch(key -> !key.contains("127.0.0.1") && !key.contains("user@example.com"));
        assertThat(fingerprints.hash("127.0.0.1")).hasSize(64)
                .doesNotContain("127.0.0.1");
    }

    @Test
    void exceedsLoginIpLimitWithRateLimitedCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(11L);

        assertThatThrownBy(() -> rateLimiter.checkLogin("10.0.0.1", "user@example.com"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(429);
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.retryAfterSeconds()).isPositive();
                });
    }

    @Test
    void registerUsesEmailDailyCapOfThree() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L, 2L, 4L);

        rateLimiter.checkRegister("10.0.0.1", "new@example.com");
        assertThatThrownBy(() -> rateLimiter.checkRegister("10.0.0.2", "new@example.com"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RATE_LIMITED"));
    }

    @Test
    void redisFailureKeepsAuthSpecificDependencyError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> rateLimiter.checkCsrf("10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    assertThat(exception.getMessage()).isEqualTo("认证服务暂不可用，请稍后再试");
                });
    }

    @Test
    void csrfKeyUsesHashedIpAndConfiguredWindow() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        rateLimiter.checkCsrf("192.168.1.1");

        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThat(fingerprints.hash("192.168.1.1")).doesNotContain("192.168.1.1");
        assertThat(properties.getCsrfIpWindow()).isEqualTo(Duration.ofMinutes(1));
    }
}
