package com.getjobs.cloud.logging;

import java.util.regex.Pattern;

/**
 * 在基础设施异常文本进入日志前清理常见凭证格式。
 * 业务日志仍应坚持字段白名单，不能依赖该类记录请求体。
 *
 * <p>覆盖：Bearer/JWT、插件 Token、命名密钥对（password/passwd、Authorization、
 * Cookie/Set-Cookie、access/refresh token、API Key、SecretId/SecretKey 等）、
 * 数据库连接串内嵌密码、邮箱与手机号。所有命中一律替换为固定
 * {@code [REDACTED]}，绝不输出原值片段。</p>
 */
public final class SensitiveDataSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}(?![A-Za-z0-9_-])"
    );
    /** 插件 Token：ajp_plg_ 前缀 + 无填充 Base64URL 随机串。 */
    private static final Pattern PLUGIN_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])ajp_plg_[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])"
    );
    /** 腾讯云 SecretId 固定前缀 AKID。 */
    private static final Pattern TENCENT_SECRET_ID = Pattern.compile(
            "(?<![A-Za-z0-9])AKID[A-Za-z0-9]{16,}(?![A-Za-z0-9])"
    );
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie|set[_-]?cookie|password|passwd|token|access[_-]?token"
                    + "|refresh[_-]?token|session|csrf|api[_-]?key|access[_-]?key|apikey|secret"
                    + "|secretid|secretkey|private[_-]?key|x-api-key)"
                    + "\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)"
    );
    /**
     * 数据库连接串内嵌密码：scheme://user:password@host，常见于 JDBC/URI 形式的异常文本。
     */
    private static final Pattern DATABASE_URL_PASSWORD = Pattern.compile(
            "(?i)((?:jdbc:)?(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis|rediss)"
                    + "://[^\\s/@:]+:)([^@\\s]+)@"
    );
    /** 中国大陆手机号：11 位，1[3-9] 开头。 */
    private static final Pattern CN_MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 国际电话：+国家码(1-4位) + 至少两组 3-4 位号码段，允许空格/连字符分隔。 */
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile(
            "(?<!\\d)\\+[1-9]\\d{0,3}(?:[\\s-]?\\d{3,4}){2,4}(?!\\d)"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])"
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        sanitized = JWT.matcher(sanitized).replaceAll(REDACTED);
        sanitized = PLUGIN_TOKEN.matcher(sanitized).replaceAll(REDACTED);
        sanitized = TENCENT_SECRET_ID.matcher(sanitized).replaceAll(REDACTED);
        sanitized = DATABASE_URL_PASSWORD.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
        sanitized = SECRET_PAIR.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = CN_MOBILE.matcher(sanitized).replaceAll(REDACTED);
        sanitized = INTERNATIONAL_PHONE.matcher(sanitized).replaceAll(REDACTED);
        return EMAIL.matcher(sanitized).replaceAll(REDACTED);
    }
}
