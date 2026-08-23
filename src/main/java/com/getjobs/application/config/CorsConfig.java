package com.getjobs.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS跨域配置（本地桌面应用）。
 *
 * <p>来源一律从 {@link AppCorsProperties}（APP_ALLOWED_ORIGINS /
 * APP_ALLOWED_EXTENSION_ORIGINS）读取精确值，不再使用任何通配符：
 * Web 前端默认仅本机 6866 端口；扩展接口只接受 manifest 固定公钥派生的
 * 精确开发扩展 ID，生产环境必须显式配置发布扩展 ID。</p>
 */
@Configuration
@EnableConfigurationProperties(AppCorsProperties.class)
public class CorsConfig {
    private static final List<String> BOSS_EXTENSION_API_PATHS = List.of(
            "/api/boss/chrome/**",
            "/api/boss/ai-keywords",
            "/api/boss/jobs/*/delivery-result"
    );

    @Bean
    public CorsFilter corsFilter(AppCorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        CorsConfiguration localFrontendConfig = baseConfiguration();
        localFrontendConfig.setAllowedOrigins(properties.getAllowedOrigins());

        CorsConfiguration bossExtensionConfig = baseConfiguration();
        bossExtensionConfig.setAllowedOrigins(buildExtensionAwareOrigins(properties));
        bossExtensionConfig.setAllowCredentials(false);

        // 必须先注册更具体的扩展接口，再注册全局本地前端规则。
        for (String path : BOSS_EXTENSION_API_PATHS) {
            source.registerCorsConfiguration(path, bossExtensionConfig);
        }
        source.registerCorsConfiguration("/**", localFrontendConfig);

        return new CorsFilter(source);
    }

    /**
     * 扩展接口同时允许本机前端与精确扩展来源。扩展调用不使用 Cookie，
     * 关闭凭据避免与带凭据的前端规则混用。
     */
    private List<String> buildExtensionAwareOrigins(AppCorsProperties properties) {
        java.util.ArrayList<String> origins = new java.util.ArrayList<>(properties.getAllowedOrigins());
        origins.addAll(properties.getAllowedExtensionOrigins());
        return List.copyOf(origins);
    }

    private CorsConfiguration baseConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedHeaders(List.of(
                "Accept",
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "If-Match",
                "X-CSRF-TOKEN"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }
}
