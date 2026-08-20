package com.getjobs.cloud.quota;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FREE 默认额度与环境覆盖的绑定测试：默认 20/10，可通过非敏感环境属性覆盖，
 * 且代码层保留全部套餐代码常量。
 */
class QuotaPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(QuotaProperties.class)
    static class BindContext {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindContext.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("api"));

    @Test
    void freeDefaultsAre20AnalysisAnd10Delivery() {
        runner.run(assertProperties(properties -> {
            assertThat(properties.analysisLimit()).isEqualTo(20);
            assertThat(properties.deliveryLimit()).isEqualTo(10);
        }));
    }

    @Test
    void freeLimitsAreOverridableFromSpringProperties() {
        runner.withPropertyValues(
                        "app.quota.free.analysis=50",
                        "app.quota.free.delivery=25"
                )
                .run(assertProperties(properties -> {
                    assertThat(properties.analysisLimit()).isEqualTo(50);
                    assertThat(properties.deliveryLimit()).isEqualTo(25);
                }));
    }

    @Test
    void planAndResourceConstantsAreStable() {
        assertThat(QuotaConstants.PLAN_FREE).isEqualTo("FREE");
        assertThat(QuotaConstants.PLAN_MONTHLY).isEqualTo("MONTHLY");
        assertThat(QuotaConstants.PLAN_PREMIUM_MONTHLY).isEqualTo("PREMIUM_MONTHLY");
        assertThat(QuotaConstants.PLAN_JOB_SEASON).isEqualTo("JOB_SEASON");
        assertThat(QuotaConstants.PLAN_COACHING).isEqualTo("COACHING");
        assertThat(QuotaConstants.RESOURCE_AI_ANALYSIS).isEqualTo("AI_ANALYSIS");
        assertThat(QuotaConstants.RESOURCE_DELIVERY_CONFIRM).isEqualTo("DELIVERY_CONFIRM");
        assertThat(QuotaConstants.RESET_CYCLE_MONTHLY).isEqualTo("MONTHLY");
    }

    private static ContextConsumer<AssertableApplicationContext> assertProperties(
            java.util.function.Consumer<QuotaProperties> assertions
    ) {
        return context -> assertions.accept(context.getBean(QuotaProperties.class));
    }
}
