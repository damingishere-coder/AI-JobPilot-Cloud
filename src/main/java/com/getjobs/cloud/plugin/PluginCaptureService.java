package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.delivery.DeliveryService;
import com.getjobs.cloud.plugin.PluginRepository.CaptureOutcome;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Plugin job capture upload. Every field is normalized and bounded before it
 * reaches PostgreSQL, and only whitelisted normalized fields are persisted:
 * captured jobs are written into the V4 {@code app.job_posts} pool so they are
 * immediately visible to the Web {@code /api/jobs} list/detail and the
 * match/delivery flow. There is no raw payload column: client-supplied unknown
 * keys, cookies, tokens, platform security ids or embedded HTML can never be
 * stored. The user id comes exclusively from the authenticated
 * {@link PluginPrincipal} — request bodies cannot override it (unknown JSON
 * fields are rejected by the strict deserializer). One row per
 * (user_id, platform, external_job_id) with a server-side SHA-256 fingerprint
 * of the canonical platform + platform job id: duplicate uploads only refresh
 * last_seen_at and return {@code duplicate}, never touching status, matches or
 * delivery state.
 */
@Service
@Profile("api")
public class PluginCaptureService {
    private static final Logger log = LoggerFactory.getLogger(PluginCaptureService.class);

    public static final int MAX_BATCH_ITEMS = 100;
    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_BENEFITS_ITEMS = 30;
    private static final int MAX_BENEFIT_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 20000;
    /** job_posts.location is varchar(160); city+district join must fit. */
    private static final int MAX_LOCATION_LENGTH = 160;
    /** Offset-less ISO local datetimes use the project's unified default zone. */
    private static final ZoneId LOCAL_DATETIME_ZONE = ZoneId.of("Asia/Shanghai");
    /** Clock-skew tolerance for future capturedAt values. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofHours(24);
    private static final String CREATED = "created";
    private static final String DUPLICATE = "duplicate";
    private static final String FAILED = "failed";

    /**
     * Sensitive content denylist (same approach as the delivery messages):
     * no captured field may carry cookie/credential/platform-security markers.
     * Anything matching is rejected so the value can never reach the database.
     */
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "cookie", "authorization", "bearer", "password", "token=", "localstorage",
            "sessionstorage", "securityid", "encryptbossid", "lid=", "accesskey",
            "secretkey", "x-api-key"
    );

    private final PluginRepository repository;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final AuditWriter audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PluginCaptureService(
            PluginRepository repository,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            AuditWriter audit,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** Single job capture: created on first upload, duplicate on re-upload. */
    public PluginModels.CaptureResult capture(PluginPrincipal principal, PluginModels.CaptureJobRequest request) {
        if (request == null) {
            throw validation("请求体不能为空");
        }
        CapturedJob job = normalize(request);
        CaptureOutcome outcome;
        try {
            outcome = upsert(principal, job);
        } catch (DataAccessException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "岗位上传服务暂不可用，请稍后再试", true, 5, List.of()
            );
        }
        auditCapture(principal, outcome.inserted() ? 1 : 0, outcome.inserted() ? 0 : 1, 0, 1);
        return new PluginModels.CaptureResult(outcome.id(), outcome.inserted() ? CREATED : DUPLICATE);
    }

    /**
     * Batch capture. Each item is validated and persisted independently:
     * valid items return created/duplicate, invalid items return failed with a
     * bounded error code/message; the request itself succeeds as long as it is
     * well-formed and within {@link #MAX_BATCH_ITEMS}.
     */
    public PluginModels.CaptureBatchResult captureBatch(
            PluginPrincipal principal, PluginModels.CaptureBatchRequest request
    ) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw validation("items 不能为空");
        }
        if (request.items().size() > MAX_BATCH_ITEMS) {
            throw validation("批量上传最多 " + MAX_BATCH_ITEMS + " 个岗位");
        }

        List<PluginModels.CaptureBatchItem> results = new ArrayList<>();
        int created = 0;
        int duplicates = 0;
        int failed = 0;
        for (PluginModels.CaptureJobRequest item : request.items()) {
            try {
                CapturedJob job = normalize(item);
                CaptureOutcome outcome = upsert(principal, job);
                boolean inserted = outcome.inserted();
                if (inserted) {
                    created++;
                } else {
                    duplicates++;
                }
                results.add(new PluginModels.CaptureBatchItem(
                        outcome.id(), inserted ? CREATED : DUPLICATE, null, null
                ));
            } catch (ApiException exception) {
                failed++;
                results.add(new PluginModels.CaptureBatchItem(
                        null, FAILED, exception.code(), exception.getMessage()
                ));
            } catch (DataAccessException exception) {
                failed++;
                results.add(new PluginModels.CaptureBatchItem(
                        null, FAILED, "DEPENDENCY_UNAVAILABLE", "岗位上传服务暂不可用"
                ));
            }
        }
        auditCapture(principal, created, duplicates, failed, request.items().size());
        // 每项状态数与 items 严格一致：total 等于 items 长度，created/duplicates/failed 为其和。
        return new PluginModels.CaptureBatchResult(
                results, created, duplicates, failed, request.items().size()
        );
    }

    private CaptureOutcome upsert(PluginPrincipal principal, CapturedJob job) {
        return transactions.execute(status -> tenants.execute(principal.userId(), () ->
                repository.upsertCapturedJob(
                        principal.userId(),
                        job.platform(), job.platformJobId(), job.fingerprint(),
                        job.jobUrl(), job.title(), job.salary(), job.location(),
                        job.companyName(), job.experience(), job.education(),
                        job.jobDescription(), job.companyInfoJson(), job.skillsJson(),
                        job.welfareJson(), job.capturedAt()
                )
        ));
    }

    /** One PLUGIN_JOB_CAPTURED audit row per upload request, whitelist details only. */
    private void auditCapture(PluginPrincipal principal, int created, int duplicates, int failed, int total) {
        try {
            audit.append(
                    principal.userId(), "PLUGIN", principal.deviceId(), "PLUGIN_JOB_CAPTURED",
                    "PLUGIN_CAPTURED_JOB", null, "SUCCESS",
                    MDC.get(RequestIdFilter.MDC_KEY), null, null,
                    Map.of("created", created, "duplicates", duplicates, "failed", failed, "total", total)
            );
        } catch (DataAccessException exception) {
            // The upload already committed; a failed audit row must not surface
            // as an error that makes the client retry and double-upload.
            log.warn("PLUGIN_JOB_CAPTURED 审计写入失败；岗位上传已提交");
        }
    }

    // ---- normalization & validation ----

    private CapturedJob normalize(PluginModels.CaptureJobRequest request) {
        String platform = normalizePlatform(request.platform());
        String platformJobId = normalizeId(request.platformJobId(), "platformJobId", 160);
        String jobUrl = normalizeUrl(request.jobUrl(), platform);
        String title = text(request.title(), "title", 240, true);
        String salary = text(request.salary(), "salary", 120, false);
        String city = text(request.city(), "city", 120, false);
        String district = text(request.district(), "district", 120, false);
        // job_posts.company_name 有非空 CHECK：API 层同样强制，与扩展端校验一致。
        String companyName = text(request.companyName(), "companyName", 240, true);
        String companySize = text(request.companySize(), "companySize", 80, false);
        String industry = text(request.industry(), "industry", 120, false);
        String experience = text(request.experience(), "experience", 120, false);
        String education = text(request.education(), "education", 120, false);
        List<String> benefits = normalizeBenefits(request.benefits());
        String jobDescription = normalizeDescription(request.jobDescription());
        String hrName = text(request.hrName(), "hrName", 120, false);
        Instant capturedAt = normalizeCapturedAt(request.capturedAt());

        // location：city/district 稳定拼接的可读形式，严格限制在列宽内。
        String location = joinLocation(city, district);
        // company_info 只允许白名单键：companySize/industry/district/hrName。
        java.util.LinkedHashMap<String, Object> companyInfo = new java.util.LinkedHashMap<>();
        if (companySize != null) {
            companyInfo.put("companySize", companySize);
        }
        if (industry != null) {
            companyInfo.put("industry", industry);
        }
        if (district != null) {
            companyInfo.put("district", district);
        }
        if (hrName != null) {
            companyInfo.put("hrName", hrName);
        }

        return new CapturedJob(
                platform, platformJobId, fingerprint(platform, platformJobId),
                jobUrl, title, salary, location, companyName, experience, education,
                jobDescription, capturedAt,
                json(companyInfo),
                json(List.of()),
                json(benefits)
        );
    }

    /**
     * platform 归一化为服务端规范枚举：只支持 BOSS / ZHILIAN（忽略大小写与
     * 首尾空白），其它任何值都是 400——与 job_posts 的受控枚举 CHECK 一致。
     */
    private String normalizePlatform(String raw) {
        String value = text(raw, "platform", 32, true);
        String upper = value.toUpperCase(Locale.ROOT);
        if (!"BOSS".equals(upper) && !"ZHILIAN".equals(upper)) {
            throw validation("platform 只支持 BOSS 或 ZHILIAN");
        }
        return upper;
    }

    /** job_posts.fingerprint：服务端 SHA-256（规范化 platform + platform_job_id），绝不采用客户端提交值。 */
    private static String fingerprint(String platform, String platformJobId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((platform + ":" + platformJobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** city·district 稳定拼接；超长时退化为 city（再退 district），恒不超过列宽。 */
    private static String joinLocation(String city, String district) {
        if (city != null && district != null) {
            String combined = city + "·" + district;
            if (combined.length() <= MAX_LOCATION_LENGTH) {
                return combined;
            }
            return city.length() <= MAX_LOCATION_LENGTH ? city : city.substring(0, MAX_LOCATION_LENGTH);
        }
        if (city != null) {
            return city.length() <= MAX_LOCATION_LENGTH ? city : city.substring(0, MAX_LOCATION_LENGTH);
        }
        if (district != null) {
            return district.length() <= MAX_LOCATION_LENGTH ? district : district.substring(0, MAX_LOCATION_LENGTH);
        }
        return null;
    }

    private String normalizeId(String raw, String field, int max) {
        String value = text(raw, field, max, true);
        if (value.matches(".*[\\s\\p{Cntrl}].*")) {
            throw validation(field + " 包含不允许的内容");
        }
        return value;
    }

    /**
     * Job URLs must be plain http/https links without credentials, ports,
     * whitespace, control characters, percent-encoding, backslashes or dot
     * segments (the same path-smuggling guards as the delivery URL checks).
     * The stored value is normalized to scheme + lowercase host + path;
     * tracking query parameters and fragments are stripped.
     */
    private String normalizeUrl(String raw, String platform) {
        if (raw == null) {
            throw validation("jobUrl 不能为空");
        }
        String value = raw.trim();
        if (value.length() > MAX_URL_LENGTH) {
            throw validation("jobUrl 过长");
        }
        if (value.matches(".*[\\s\\p{Cntrl}].*")) {
            throw validation("jobUrl 不是合法链接");
        }
        if (value.indexOf('%') >= 0 || value.indexOf('\\') >= 0) {
            throw validation("jobUrl 包含不允许的内容");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw validation("jobUrl 不是合法链接");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        if (scheme == null || host == null) {
            throw validation("jobUrl 不是合法链接");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw validation("jobUrl 只允许 http/https 链接");
        }
        if (uri.getPort() != -1 || uri.getUserInfo() != null) {
            throw validation("jobUrl 包含不允许的内容");
        }
        if (path == null || path.isBlank()) {
            throw validation("jobUrl 缺少路径");
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0 && segment.isEmpty()) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw validation("jobUrl 包含不允许的内容");
            }
        }
        return DeliveryService.normalizeTrustedJobUrl(value, platform)
                .orElseThrow(() -> validation("jobUrl 不是对应平台的受信任岗位详情链接"));
    }

    /**
     * Plain-text description: scripts/styles and every other tag are removed,
     * control characters collapse to spaces, and only entity names that cannot
     * reintroduce markup are decoded. Bounded length, single whitespace runs.
     */
    private String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_DESCRIPTION_LENGTH) {
            throw validation("jobDescription 不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return assertClean("jobDescription", cleaned);
    }

    private List<String> normalizeBenefits(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_BENEFITS_ITEMS) {
            throw validation("benefits 最多 " + MAX_BENEFITS_ITEMS + " 项");
        }
        List<String> cleaned = new ArrayList<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String value = item
                    .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?is)<[^>]+>", " ")
                    .replaceAll("\\p{Cntrl}", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_BENEFIT_LENGTH) {
                throw validation("benefits 单项不能超过 " + MAX_BENEFIT_LENGTH + " 个字符");
            }
            cleaned.add(assertClean("benefits", value));
        }
        return List.copyOf(cleaned);
    }

    /**
     * Accepts ISO offset datetimes and offset-less ISO local datetimes
     * (interpreted in the project's unified default zone); rejects clearly
     * future values beyond the clock-skew tolerance.
     */
    private Instant normalizeCapturedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw validation("capturedAt 不能为空");
        }
        String value = raw.trim();
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException offsetFailure) {
            try {
                instant = LocalDateTime.parse(value).atZone(LOCAL_DATETIME_ZONE).toInstant();
            } catch (DateTimeParseException localFailure) {
                throw validation("capturedAt 必须是 ISO 时间，如 2026-08-15T10:30:00 或 2026-08-15T10:30:00Z");
            }
        }
        if (instant.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) {
            throw validation("capturedAt 不能晚于当前时间");
        }
        return instant;
    }

    /** Single-line text normalization: required check, control collapse, bound. */
    private String text(String raw, String field, int max, boolean required) {
        if (raw == null) {
            if (required) {
                throw validation(field + " 不能为空");
            }
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            if (required) {
                throw validation(field + " 不能为空");
            }
            return null;
        }
        if (cleaned.length() > max) {
            throw validation(field + " 不能超过 " + max + " 个字符");
        }
        return assertClean(field, cleaned);
    }

    /** Rejects sensitive markers and query-style parameter pairs in any field. */
    private String assertClean(String field, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                throw validation(field + " 包含不允许的内容");
            }
        }
        if (value.matches(".*\\?[^\\s]*=.*")) {
            throw validation(field + " 包含不允许的内容");
        }
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化采集字段", exception);
        }
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /** Fully normalized, server-trusted capture fields mapped onto job_posts columns. */
    private record CapturedJob(
            String platform,
            String platformJobId,
            String fingerprint,
            String jobUrl,
            String title,
            String salary,
            String location,
            String companyName,
            String experience,
            String education,
            String jobDescription,
            Instant capturedAt,
            String companyInfoJson,
            String skillsJson,
            String welfareJson
    ) {
    }
}
