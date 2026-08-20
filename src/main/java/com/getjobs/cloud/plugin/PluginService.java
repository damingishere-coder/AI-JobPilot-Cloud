package com.getjobs.cloud.plugin;

import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.SecurityFingerprintService;
import com.getjobs.cloud.plugin.PluginRepository.BindOutcome;
import com.getjobs.cloud.plugin.PluginRepository.DeviceRecord;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plugin binding and device management. Bind codes live in Redis; devices and
 * token hashes live in PostgreSQL. The plaintext token is created here with
 * a CSPRNG and returned exactly once. Device write, token-hash write, device
 * read-back and the PLUGIN_DEVICE_BOUND audit run in ONE database transaction:
 * if the audit or the read-back fails the whole bind rolls back, so a 500 can
 * never leave behind an active token whose plaintext was lost.
 */
@Service
@Profile("api")
public class PluginService {
    public static final List<String> DEFAULT_SCOPES = List.of(
            "device:read", "tasks:read", "tasks:write", "jobs:write"
    );
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of("BOSS", "ZHILIAN");
    private static final Pattern EXTENSION_VERSION_PATTERN = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}");
    private static final int INSTALLATION_ID_MIN = 16;
    private static final int INSTALLATION_ID_MAX = 128;

    private final PluginBindCodeService bindCodes;
    private final PluginRepository devices;
    private final PluginProperties properties;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final AuditWriter audit;
    private final SecurityFingerprintService fingerprints;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public PluginService(
            PluginBindCodeService bindCodes,
            PluginRepository devices,
            PluginProperties properties,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            AuditWriter audit,
            SecurityFingerprintService fingerprints,
            Clock clock,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.bindCodes = bindCodes;
        this.devices = devices;
        this.properties = properties;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.fingerprints = fingerprints;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a one-time bind code for the current Web user. PostgreSQL is the
     * source of truth (hash only); Redis caches the plaintext result for
     * idempotent replay with the same Idempotency-Key and expires it with the
     * code. Concurrent requests with the same key serialize on a PostgreSQL
     * advisory lock and all observe the same code.
     */
    public PluginModels.BindCodeResult createBindCode(UUID userId, String idempotencyKey) {
        String keyHash = bindCodes.hash(userId + ":" + idempotencyKey);
        try {
            return transactions.execute(status -> {
                bindCodes.lockCreate(userId, keyHash);
                PluginModels.BindCodeResult cached = bindCodes.replay(userId, keyHash);
                if (cached != null) {
                    return cached;
                }
                PluginModels.BindCodeResult fresh = bindCodes.create(userId);
                bindCodes.cacheForReplay(userId, keyHash, fresh);
                return fresh;
            });
        } catch (DataAccessException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "绑定码服务暂不可用，请稍后再试", true, 5, List.of()
            );
        }
    }

    /** Internal bind result carrying the owning user for audit attribution. */
    public record BoundDevice(UUID userId, PluginModels.BindResult result) {
    }

    /**
     * Anonymous bind with a one-time code; creates/reuses the device and a
     * token. The device/token writes, the device read-back and the
     * PLUGIN_DEVICE_BOUND audit commit or roll back together.
     */
    public BoundDevice bindWithOwner(
            PluginModels.BindRequest request, String remoteAddress, String userAgent, String requestId
    ) {
        List<String> capabilities = validateRequest(request);

        String normalizedCode = normalizeCode(request.bindCode());
        // Keyed HMAC fingerprint: a dictionary of plain IP SHA-256 values
        // cannot be reversed against this Redis key or the audit ip_hash.
        String ipFingerprint = fingerprints.hash(remoteAddress);
        String ipRateKey = "ai-jobpilot:plugin:bind:rate:" + ipFingerprint;
        try {
            bindCodes.checkIpLimit(ipRateKey,
                    properties.getBindIpLimit(), properties.getBindIpWindow());
            bindCodes.checkAttemptLimit(normalizedCode);
        } catch (DataAccessException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "绑定服务暂不可用，请稍后再试", true, 5, List.of()
            );
        }

        String token = generateToken();
        String tokenHash = bindCodes.hash(token);
        String installationIdHash = bindCodes.hash(request.installationId());
        Instant expiresAt = clock.instant().plus(properties.getTokenTtl());

        String capabilitiesJson = jsonArray(capabilities);
        String scopesJson = jsonArray(DEFAULT_SCOPES);
        // Same keyed fingerprint service the Web audit trail uses, so the
        // audit row never carries a dictionary-reversible IP hash.
        String ipHash = ipFingerprint;

        return transactions.execute(status -> {
            // The one-shot code consumption runs inside the SAME transaction
            // as the device/token writes: a bind can never consume a code
            // without issuing its device/token, or vice versa.
            UUID userId;
            try {
                userId = bindCodes.consume(normalizedCode).orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "BIND_CODE_INVALID", "绑定码无效或已过期"
                ));
            } catch (DataAccessException exception) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                        "绑定服务暂不可用，请稍后再试", true, 5, List.of()
                );
            }

            BindOutcome outcome = devices.bindDevice(
                    userId,
                    installationIdHash,
                    request.deviceName().trim(),
                    blankToNull(request.browserName()),
                    blankToNull(request.browserVersion()),
                    request.extensionVersion().trim(),
                    capabilitiesJson,
                    tokenPrefix(token),
                    tokenHash,
                    scopesJson,
                    expiresAt,
                    properties.getMaxDevicesPerUser()
            );

            if ("ACCOUNT_DISABLED".equals(outcome.outcome())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号当前不可用"
                );
            }
            if (!outcome.ok()) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_LIMIT_EXCEEDED",
                        "绑定设备数量已达上限，请先在网页后台撤销不再使用的设备"
                );
            }

            DeviceRecord device = tenants.execute(userId, () ->
                    devices.findDevice(userId, outcome.deviceId()).orElseThrow(() -> new ApiException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "绑定服务出现异常，请重试"
                    ))
            );

            // Same transaction as the device/token writes: a failure here rolls
            // the bind back instead of stranding an active token.
            audit.append(
                    userId, "PLUGIN", device.id(), "PLUGIN_DEVICE_BOUND",
                    "PLUGIN_DEVICE", device.id(), "SUCCESS",
                    requestId, ipHash, AuditLogService.summarizeUserAgent(userAgent),
                    Map.of("extensionVersion", device.extensionVersion(),
                            "capabilities", device.capabilities())
            );

            return new BoundDevice(userId, new PluginModels.BindResult(
                    PluginRepository.toView(device),
                    new PluginModels.TokenValue(token, expiresAt, DEFAULT_SCOPES)
            ));
        });
    }

    /** Devices of the current Web user. */
    public List<PluginModels.DeviceView> listDevices(UUID userId) {
        return transactions.execute(status -> tenants.execute(userId, () ->
                devices.listDevices(userId).stream().map(PluginRepository::toView).toList()
        ));
    }

    /** Revoke a device (Web action); tokens die immediately, leases are released. */
    public PluginModels.RevokeDeviceResult revokeDevice(UUID userId, UUID deviceId, String reason) {
        String safeReason = reason == null ? null : truncate(reason.trim(), 255);
        boolean revoked = transactions.execute(status -> devices.revokeDevice(userId, deviceId, safeReason));
        if (!revoked) {
            return null;
        }
        DeviceRecord device = transactions.execute(status -> tenants.execute(userId, () ->
                devices.findDevice(userId, deviceId).orElse(null)
        ));
        return new PluginModels.RevokeDeviceResult(
                deviceId, "REVOKED", device == null ? null : device.revokedAt()
        );
    }

    /**
     * Heartbeat: force-refreshes the device last_seen_at and returns the
     * trusted ids and current state. The token filter already rejected
     * revoked/expired credentials, so a valid heartbeat always reports ACTIVE.
     */
    public PluginModels.HeartbeatResponse heartbeat(PluginPrincipal principal) {
        transactions.executeWithoutResult(status ->
                devices.touch(principal.tokenId(), principal.deviceId(), 0));
        DeviceRecord device = transactions.execute(status -> tenants.execute(principal.userId(), () ->
                devices.findDevice(principal.userId(), principal.deviceId()).orElse(null)
        ));
        return new PluginModels.HeartbeatResponse(
                principal.deviceId(), principal.userId(),
                device == null ? "UNKNOWN" : device.status(),
                device == null ? null : device.lastSeenAt()
        );
    }

    /** Identity view for the authenticated plugin. */
    public PluginModels.MeResponse me(PluginPrincipal principal) {
        DeviceRecord device = transactions.execute(status -> tenants.execute(principal.userId(), () ->
                devices.findDevice(principal.userId(), principal.deviceId()).orElse(null)
        ));
        return new PluginModels.MeResponse(
                new PluginModels.MinimalUser(principal.userId(), principal.userDisplayName()),
                device == null ? null : PluginRepository.toView(device),
                new PluginModels.TokenInfo(principal.scopes(), principal.tokenExpiresAt())
        );
    }

    // ---- validation ----

    /** Validates the anonymous bind request and returns the normalized capability list. */
    private List<String> validateRequest(PluginModels.BindRequest request) {
        if (request == null) {
            throw validation("请求体不能为空");
        }
        if (request.bindCode() == null || !request.bindCode().matches("^[A-Za-z0-9]{5}-[A-Za-z0-9]{5}$")) {
            throw validation("绑定码格式不正确");
        }
        if (request.installationId() == null
                || request.installationId().length() < INSTALLATION_ID_MIN
                || request.installationId().length() > INSTALLATION_ID_MAX
                || !request.installationId().matches("^[A-Za-z0-9_-]+$")) {
            throw validation("installationId 必须为 16-128 位随机字符串");
        }
        if (request.deviceName() == null || request.deviceName().isBlank()
                || request.deviceName().trim().length() > 100) {
            throw validation("deviceName 不能为空且不能超过 100 个字符");
        }
        if (request.browserName() != null && request.browserName().length() > 40) {
            throw validation("browserName 不能超过 40 个字符");
        }
        if (request.browserVersion() != null && request.browserVersion().length() > 40) {
            throw validation("browserVersion 不能超过 40 个字符");
        }
        if (request.extensionVersion() == null
                || !EXTENSION_VERSION_PATTERN.matcher(request.extensionVersion().trim()).matches()) {
            throw validation("extensionVersion 必须为纯数字版本格式，如 1.2.0");
        }
        if (request.capabilities() == null || request.capabilities().isEmpty()) {
            throw validation("capabilities 至少需要支持一个招聘平台");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String capability : request.capabilities()) {
            String value = capability == null ? "" : capability.trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_CAPABILITIES.contains(value)) {
                throw validation("capabilities 只允许 BOSS 或 ZHILIAN");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    // ---- helpers ----

    private String generateToken() {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        return properties.getTokenPrefix() + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String tokenPrefix(String token) {
        return token.substring(0, Math.min(16, token.length()));
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化插件数组字段", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
