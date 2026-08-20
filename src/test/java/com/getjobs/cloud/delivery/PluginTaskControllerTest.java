package com.getjobs.cloud.delivery;

import com.getjobs.cloud.plugin.CurrentPlugin;
import com.getjobs.cloud.plugin.PluginPrincipal;
import com.getjobs.cloud.ratelimit.ApiRateLimiter;
import com.getjobs.cloud.ratelimit.RateLimitProperties;
import com.getjobs.cloud.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit wiring for the plugin pending poll: the endpoint is wrapped by the
 * shared Redis rate limiter on the authenticated deviceId dimension, never
 * the raw token, and a 429 short-circuits before the service is touched.
 */
class PluginTaskControllerTest {

    private static final String KEY_PREFIX = "ai-jobpilot:api:rate:plugin-task-poll:device:";

    private final CurrentPlugin currentPlugin = mock(CurrentPlugin.class);
    private final DeliveryService delivery = mock(DeliveryService.class);
    private final ApiRateLimiter rateLimiter = mock(ApiRateLimiter.class);
    private final RateLimitProperties rateLimits = new RateLimitProperties();
    private final PluginTaskController controller =
            new PluginTaskController(currentPlugin, delivery, rateLimiter, rateLimits);

    private static PluginPrincipal principal(UUID deviceId) {
        return new PluginPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), deviceId, "测试设备", "测试用户",
                List.of("tasks:read"), List.of("BOSS"), "1.2.0",
                Instant.now().plusSeconds(3600)
        );
    }

    @Test
    void pendingIsRateLimitedPerDeviceWithConfiguredBounds() {
        PluginPrincipal principal = principal(UUID.randomUUID());
        when(currentPlugin.require()).thenReturn(principal);
        when(delivery.pending(any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryModels.PendingTasksResult(List.of(), 10, Instant.now()));

        controller.pending(null, null);

        verify(rateLimiter).check(
                eq(KEY_PREFIX + principal.deviceId()),
                eq(rateLimits.getPluginTaskPollLimit()),
                eq(rateLimits.getPluginTaskPollWindow())
        );
    }

    @Test
    void differentDevicesGetSeparateRateLimitKeys() {
        PluginPrincipal first = principal(UUID.randomUUID());
        PluginPrincipal second = principal(UUID.randomUUID());
        when(currentPlugin.require()).thenReturn(first, second);
        when(delivery.pending(any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryModels.PendingTasksResult(List.of(), 10, Instant.now()));

        controller.pending(null, null);
        controller.pending(null, null);

        verify(rateLimiter).check(eq(KEY_PREFIX + first.deviceId()), anyInt(), any());
        verify(rateLimiter).check(eq(KEY_PREFIX + second.deviceId()), anyInt(), any());
        // The key carries only the server-resolved device id, never token material.
        assertThat(KEY_PREFIX + first.deviceId()).doesNotContain("ajp_plg_", "Bearer", "token");
    }

    @Test
    void rateLimitedRequestsFailWithRetryAfterBeforeTheServiceRuns() {
        when(currentPlugin.require()).thenReturn(principal(UUID.randomUUID()));
        org.mockito.Mockito.doThrow(ApiRateLimiter.rateLimited(Duration.ofMinutes(1)))
                .when(rateLimiter).check(any(), anyInt(), any());

        assertThatThrownBy(() -> controller.pending(null, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(429);
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.retryAfterSeconds()).isEqualTo(60);
                });

        verifyNoInteractions(delivery);
    }

    @Test
    void serviceReceivesOnlyTheAuthenticatedPrincipalIdentity() {
        PluginPrincipal principal = principal(UUID.randomUUID());
        when(currentPlugin.require()).thenReturn(principal);
        when(delivery.pending(any(), any(), any(), any(), any()))
                .thenReturn(new DeliveryModels.PendingTasksResult(List.of(), 10, Instant.now()));

        controller.pending(5, "BOSS");

        verify(delivery).pending(
                eq(principal.userId()), eq(principal.deviceId()), eq(principal.capabilities()),
                eq(5), eq("BOSS")
        );
        verify(delivery, never()).pending(
                eq(principal.userId()), eq(principal.deviceId()), eq(principal.capabilities()),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()
        );
    }
}
