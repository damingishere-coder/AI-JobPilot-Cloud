package com.getjobs.cloud.logging;

import java.util.regex.Pattern;

/**
 * 在基础设施异常文本进入日志前清理常见凭证格式。
 * 业务日志仍应坚持字段白名单，不能依赖该类记录请求体。
 */
public final class SensitiveDataSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie|password|passwd|token|api[_-]?key|access[_-]?key|secret|private[_-]?key)"
                    + "\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)"
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String withoutBearer = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        return SECRET_PAIR.matcher(withoutBearer).replaceAll("$1" + REDACTED);
    }
}
