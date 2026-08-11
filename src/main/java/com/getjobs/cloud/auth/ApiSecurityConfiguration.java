package com.getjobs.cloud.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.CookieSerializer;

import java.time.Clock;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("api")
@EnableConfigurationProperties(AuthProperties.class)
public class ApiSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    CookieSerializer cookieSerializer(AuthProperties properties) {
        return new DynamicSessionCookieSerializer(properties);
    }

    @Bean
    FilterRegistrationBean<AccountStatusFilter> disableAccountStatusServletRegistration(
            AccountStatusFilter filter
    ) {
        FilterRegistrationBean<AccountStatusFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UserIdParameterFilter> disableUserIdServletRegistration(
            UserIdParameterFilter filter
    ) {
        FilterRegistrationBean<UserIdParameterFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AccountStatusFilter accountStatusFilter,
            UserIdParameterFilter userIdParameterFilter,
            SecurityResponseWriter responses,
            SecurityContextRepository securityContexts,
            CsrfTokenRepository csrfTokens
    ) throws Exception {
        RequestMatcher csrfMatcher = request -> {
            if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
                return false;
            }
            if (!"/api/auth/logout".equals(request.getRequestURI())) {
                return true;
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof SessionPrincipal;
        };

        http
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfMatcher)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/livez", "/readyz", "/api/health", "/error",
                                "/actuator/health", "/actuator/health/**",
                                "/api/auth/csrf", "/api/auth/register", "/api/auth/login", "/api/auth/logout"
                        ).permitAll()
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.write(response, 401, "AUTH_REQUIRED", "请先登录", false))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof MissingCsrfTokenException || exception instanceof InvalidCsrfTokenException) {
                                responses.write(response, 403, "CSRF_INVALID", "安全校验已失效，请刷新后重试", false);
                            } else {
                                responses.write(response, 403, "FORBIDDEN", "没有权限执行该操作", false);
                            }
                        })
                )
                .addFilterAfter(userIdParameterFilter, CsrfFilter.class)
                .addFilterAfter(accountStatusFilter, SecurityContextHolderFilter.class);
        return http.build();
    }
}
