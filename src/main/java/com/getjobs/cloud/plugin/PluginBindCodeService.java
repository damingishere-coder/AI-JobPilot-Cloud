package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time bind codes with PostgreSQL as the single source of truth. Only the
 * SHA-256 hex of a code is stored; codes are single-use, expire after a
 * bounded TTL and are capped per user (the oldest ACTIVE code is
 * auto-superseded inside {@code app.create_plugin_bind_code}). Redis is used
 * only for rate limiting (per-IP and per-code attempt counters) and a
 * short-lived idempotent response cache so retries with the same
 * Idempotency-Key observe the same plaintext code; the cache expires with the
 * code and never outlives it. The plaintext code is returned exactly once and
 * is never written to PostgreSQL or the audit trail.
 *
 * <p>Creation and consumption run through {@link PluginRepository} inside the
 * caller's database transaction: consumption and device/token issuance commit
 * or roll back together in {@link PluginService}.</p>
 */
@Service
@Profile("api")
public class PluginBindCodeService {
    private static final String IDEMPOTENCY_PREFIX = "ai-jobpilot:plugin:bind-code:idem:";
    private static final String ATTEMPT_PREFIX = "ai-jobpilot:plugin:bind-code:attempts:";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PluginRepository repository;
    private final PluginProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public PluginBindCodeService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            PluginRepository repository,
            PluginProperties properties,
            Clock clock
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Mint a new bind code and persist its hash. Callers hold the transaction
     * and have already taken the per-(user, key) advisory lock and checked the
     * replay cache; the outcome maps to ACCOUNT_DISABLED when the account was
     * disabled after the request started.
     */
    public PluginModels.BindCodeResult create(UUID userId) {
        String code = generateCode();
        Instant expiresAt = clock.instant().plus(properties.getBindCodeTtl());

        String outcome = repository.createBindCode(
                userId, hash(code), expiresAt, properties.getMaxActiveBindCodes()
        );
        if ("ACCOUNT_DISABLED".equals(outcome)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号当前不可用"
            );
        }
        return new PluginModels.BindCodeResult(
                code, expiresAt, properties.getBindCodeTtl().toSeconds()
        );
    }

    /** Serializes concurrent creates for the same (user, key) in PostgreSQL. */
    public void lockCreate(UUID userId, String idempotencyKeyHash) {
        repository.lockBindCodeCreate(userId, idempotencyKeyHash);
    }

    /** Returns the cached result for the (user, key) or null when absent. */
    public PluginModels.BindCodeResult replay(UUID userId, String idempotencyKeyHash) {
        String serialized = redis.opsForValue().get(idempotencyKey(userId, idempotencyKeyHash));
        return serialized == null ? null : deserialize(serialized);
    }

    /** Caches the plaintext result for idempotent replay; expires with the code. */
    public void cacheForReplay(UUID userId, String idempotencyKeyHash, PluginModels.BindCodeResult result) {
        redis.opsForValue().set(
                idempotencyKey(userId, idempotencyKeyHash),
                serialize(result),
                properties.getBindCodeTtl()
        );
    }

    /**
     * Consume a bind code exactly once against PostgreSQL. Empty means
     * invalid, expired, superseded or already used — indistinguishable on
     * purpose so codes cannot be probed. Runs inside the caller's transaction
     * so the consumption and the device/token writes commit together.
     */
    public Optional<UUID> consume(String code) {
        Optional<UUID> owner = repository.consumeBindCode(hash(code));
        if (owner.isPresent()) {
            // Best-effort rate-limit cleanup; PostgreSQL is the only source of
            // truth, so a Redis failure here must never fail the bind.
            try {
                redis.delete(ATTEMPT_PREFIX + code);
            } catch (DataAccessException ignored) {
            }
        }
        return owner;
    }

    /**
     * Count an attempt against a bind code; rejects after the limit.
     * Callers check this before {@link #consume(String)}.
     */
    public void checkAttemptLimit(String code) {
        Long attempts = redis.opsForValue().increment(ATTEMPT_PREFIX + code);
        if (attempts != null && attempts == 1) {
            redis.expire(ATTEMPT_PREFIX + code, properties.getBindCodeTtl());
        }
        if (attempts != null && attempts > properties.getBindCodeAttemptLimit()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "绑定尝试过于频繁，请稍后再试", true, 30, List.of()
            );
        }
    }

    /** Per-IP rate limit for anonymous bind attempts. */
    public void checkIpLimit(String ipRateKey, int limit, java.time.Duration window) {
        Long count = redis.opsForValue().increment(ipRateKey);
        if (count != null && count == 1) {
            redis.expire(ipRateKey, window);
        }
        if (count != null && count > limit) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "绑定请求过于频繁，请稍后再试", true, window.toSeconds(), List.of()
            );
        }
    }

    public String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String idempotencyKey(UUID userId, String idempotencyKeyHash) {
        return IDEMPOTENCY_PREFIX + userId + ":" + idempotencyKeyHash;
    }

    private String generateCode() {
        char[] code = new char[10];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[secureRandom.nextInt(ALPHABET.length)];
        }
        return new String(code, 0, 5) + "-" + new String(code, 5, 5);
    }

    private String serialize(PluginModels.BindCodeResult result) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "bindCode", result.bindCode(),
                    "expiresAt", result.expiresAt().toString(),
                    "expiresInSeconds", result.expiresInSeconds()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化绑定码缓存", exception);
        }
    }

    private PluginModels.BindCodeResult deserialize(String json) {
        try {
            var node = objectMapper.readTree(json);
            return new PluginModels.BindCodeResult(
                    node.path("bindCode").asText(),
                    Instant.parse(node.path("expiresAt").asText()),
                    node.path("expiresInSeconds").asLong()
            );
        } catch (JsonProcessingException | java.time.format.DateTimeParseException exception) {
            throw new IllegalStateException("无法读取绑定码缓存", exception);
        }
    }
}
