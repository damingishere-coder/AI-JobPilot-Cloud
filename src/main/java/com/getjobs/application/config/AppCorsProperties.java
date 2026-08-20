package com.getjobs.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地桌面应用的 CORS 来源配置，全部来自环境变量，禁止通配符：
 * <ul>
 *   <li>{@code APP_ALLOWED_ORIGINS} —— Web 前端来源（默认仅本机 6866 开发端口）；</li>
 *   <li>{@code APP_ALLOWED_EXTENSION_ORIGINS} —— 精确的 chrome-extension 来源
 *       （默认与 chrome-extension/manifest.json 固定公钥派生的开发扩展 ID 一致）。</li>
 * </ul>
 * 部署环境必须显式配置；任何通配符或模式写法都会在启动时直接失败。
 */
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {
    private static final String EXTENSION_ORIGIN_PATTERN = "chrome-extension://[a-p]{32}";

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:6866",
            "http://127.0.0.1:6866"
    ));
    private List<String> allowedExtensionOrigins = new ArrayList<>(List.of(
            "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc"
    ));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = normalizeOrigins(allowedOrigins, "APP_ALLOWED_ORIGINS");
    }

    public List<String> getAllowedExtensionOrigins() {
        return allowedExtensionOrigins;
    }

    public void setAllowedExtensionOrigins(List<String> allowedExtensionOrigins) {
        this.allowedExtensionOrigins = normalizeExtensionOrigins(allowedExtensionOrigins);
    }

    private static List<String> normalizeOrigins(List<String> values, String envName) {
        List<String> normalized = new ArrayList<>();
        if (values != null) {
            for (String raw : values) {
                String value = raw == null ? "" : raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (!value.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
                    throw new IllegalArgumentException(
                            envName + " 只允许 http(s)://host[:port] 形式的精确来源，"
                                    + "不支持通配符或模式：" + value
                    );
                }
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    envName + " 不能为空；部署环境必须显式配置允许的前端来源"
            );
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeExtensionOrigins(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values != null) {
            for (String raw : values) {
                String value = raw == null ? "" : raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (!value.matches(EXTENSION_ORIGIN_PATTERN)) {
                    throw new IllegalArgumentException(
                            "APP_ALLOWED_EXTENSION_ORIGINS 只允许精确的 chrome-extension://<32位a-p扩展ID>，"
                                    + "不支持通配符或模式：" + value
                    );
                }
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }
}
