package com.getjobs.cloud.plugin;

import com.getjobs.cloud.auth.SecurityResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

/**
 * Stateless plugin-token security chain. It matches only the plugin-facing
 * endpoints: the anonymous bind call, the device identity view and the task
 * execution API. Everything else falls through to the Web session chain, so
 * Web endpoints can never be reached with a plugin token and vice versa.
 */
@Configuration
@EnableWebSecurity
@Profile("api")
@EnableConfigurationProperties(PluginProperties.class)
public class PluginSecurityConfiguration {

    /**
     * The token filter must only run inside this security chain; disable the
     * servlet-container auto-registration that would apply it to every request.
     */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<PluginTokenAuthenticationFilter>
    disablePluginTokenServletRegistration(PluginTokenAuthenticationFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<PluginTokenAuthenticationFilter> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain pluginSecurityFilterChain(
            HttpSecurity http,
            PluginTokenAuthenticationFilter tokenFilter,
            SecurityResponseWriter responses
    ) throws Exception {
        http.securityMatcher(
                        "/api/plugin/bind",
                        "/api/plugin/me",
                        "/api/plugin/tasks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/plugin/bind").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/plugin/me")
                        .hasAuthority("SCOPE_device:read")
                        .requestMatchers(HttpMethod.GET, "/api/plugin/tasks/pending")
                        .hasAuthority("SCOPE_tasks:read")
                        // start claims and executes: the token must carry BOTH scopes.
                        .requestMatchers(HttpMethod.POST, "/api/plugin/tasks/*/start")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("SCOPE_tasks:read"),
                                AuthorityAuthorizationManager.hasAuthority("SCOPE_tasks:write")
                        ))
                        .requestMatchers(HttpMethod.POST, "/api/plugin/tasks/*/success")
                        .hasAuthority("SCOPE_tasks:write")
                        .requestMatchers(HttpMethod.POST, "/api/plugin/tasks/*/fail")
                        .hasAuthority("SCOPE_tasks:write")
                        .requestMatchers(HttpMethod.POST, "/api/plugin/tasks/*/pause")
                        .hasAuthority("SCOPE_tasks:write")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.write(response, 401, "PLUGIN_TOKEN_INVALID", "缺少有效的插件凭证", false))
                        .accessDeniedHandler((request, response, exception) ->
                                responses.write(response, 403, "FORBIDDEN", "插件没有权限执行该操作", false)))
                .addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
