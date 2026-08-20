package com.getjobs.cloud.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for the protective rate-limit parameters. The plugin pending
 * poll uses the device dimension; the job capture bounds are reserved for the
 * not-yet-implemented capture endpoints (P6) and must already be overridable
 * from environment/Spring properties without any code change.
 */
class RateLimitPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class BindContext {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindContext.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("api"));

    @Test
    void pollDefaultsFitThePollAfterSecondsCadence() {
        runner.run(assertProperties(properties -> {
            // poll-after-seconds=10 -> 6 polls/minute per device; the default
            // 60/minute leaves 10x headroom for a normal device.
            assertThat(properties.getPluginTaskPollLimit()).isEqualTo(60);
            assertThat(properties.getPluginTaskPollWindow()).isEqualTo(Duration.ofMinutes(1));
        }));
    }

    @Test
    void pollBoundsAreOverridableFromSpringProperties() {
        runner.withPropertyValues(
                        "app.rate-limit.plugin-task-poll-limit=12",
                        "app.rate-limit.plugin-task-poll-window=45s"
                )
                .run(assertProperties(properties -> {
                    assertThat(properties.getPluginTaskPollLimit()).isEqualTo(12);
                    assertThat(properties.getPluginTaskPollWindow()).isEqualTo(Duration.ofSeconds(45));
                }));
    }

    @Test
    void captureBoundsAreReservedAndOverridable() {
        runner.run(assertProperties(properties -> {
            assertThat(properties.getPluginJobCaptureLimit()).isEqualTo(30);
            assertThat(properties.getPluginJobCaptureWindow()).isEqualTo(Duration.ofMinutes(1));
        }));
        runner.withPropertyValues(
                        "app.rate-limit.plugin-job-capture-limit=5",
                        "app.rate-limit.plugin-job-capture-window=10m"
                )
                .run(assertProperties(properties -> {
                    assertThat(properties.getPluginJobCaptureLimit()).isEqualTo(5);
                    assertThat(properties.getPluginJobCaptureWindow()).isEqualTo(Duration.ofMinutes(10));
                }));
    }

    @Test
    void nonPositiveBoundsFailBindingInsteadOfDisablingTheLimit() {
        runner.withPropertyValues("app.rate-limit.plugin-task-poll-limit=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.rate-limit.plugin-job-capture-limit=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ContextConsumer<AssertableApplicationContext> assertProperties(
            java.util.function.Consumer<RateLimitProperties> assertions
    ) {
        return context -> assertions.accept(context.getBean(RateLimitProperties.class));
    }
}
