package com.getjobs.cloud.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("worker")
@SpringJUnitWebConfig(WorkerSecurityConfigurationTest.TestConfiguration.class)
class WorkerSecurityConfigurationTest {
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
    void keepsCsrfProtectionEnabledForWorkerRequests() {
        assertThat(springSecurityFilterChain.getFilterChains())
                .flatExtracting(chain -> chain.getFilters())
                .anyMatch(CsrfFilter.class::isInstance);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(WorkerSecurityConfiguration.class)
    static class TestConfiguration {
    }
}
