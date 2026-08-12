package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
 * One-time bind codes in Redis. Codes are short-lived, single-use, capped per
 * user and evict the oldest when the cap is reached. Creation (idempotency
 * lookup, user index, value and cap eviction) and consumption (get + delete +
 * index cleanup) each run in a single Lua script so concurrent requests with
 * the same idempotency key always observe the same code and a code can only
 * ever be consumed once. The plaintext code is returned exactly once and
 * never written to PostgreSQL or the audit trail.
 */
@Service
@Profile("api")
public class PluginBindCodeService {
    private static final String VALUE_PREFIX = "ai-jobpilot:plugin:bind-code:value:";
    private static final String USER_INDEX_PREFIX = "ai-jobpilot:plugin:bind-code:user:";
    private static final String IDEMPOTENCY_PREFIX = "ai-jobpilot:plugin:bind-code:idem:";
    private static final String ATTEMPT_PREFIX = "ai-jobpilot:plugin:bind-code:attempts:";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * Atomic create-or-replay. KEYS[1] = user index, KEYS[2] = idempotency key.
     * ARGV[1] = current epoch millis (expiry eviction bound), ARGV[2] = new code
     * expiry epoch millis (index score), ARGV[3] = max active codes,
     * ARGV[4] = value key prefix, ARGV[5] = new code, ARGV[6] = owner user id,
     * ARGV[7] = TTL millis, ARGV[8] = serialized BindCodeResult. Returns the
     * serialized result of the code the caller must use (cached or new).
     * Only expired members (score &lt; now) are purged up front; unexpired
     * members survive so a second/third code never evicts a live first one.
     * The cap then evicts the OLDEST member (lowest score) only when exceeded,
     * deleting its value key in the same atomic step.
     */
    private static final DefaultRedisScript<String> CREATE_SCRIPT = new DefaultRedisScript<>("""
            local cached = redis.call('GET', KEYS[2])
            if cached then
              return cached
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            local max = tonumber(ARGV[3])
            if count >= max then
              local removed = redis.call('ZPOPMIN', KEYS[1], count - max + 1)
              for i = 1, #removed, 2 do
                redis.call('DEL', ARGV[4] .. removed[i])
              end
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
            redis.call('SET', ARGV[4] .. ARGV[5], ARGV[6], 'PX', ARGV[7])
            redis.call('SET', KEYS[2], ARGV[8], 'PX', ARGV[7])
            return ARGV[8]
            """, String.class);

    /**
     * Atomic one-shot consume. KEYS[1] = value key, KEYS[2] = attempts key.
     * ARGV[1] = user index prefix, ARGV[2] = code. Returns the owner user id
     * or nil when the code is missing, expired or already used.
     */
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return nil
            end
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            redis.call('ZREM', ARGV[1] .. value, ARGV[2])
            return value
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PluginProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public PluginBindCodeService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            PluginProperties properties,
            Clock clock
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Create a new bind code for the user. Idempotent per user + key hash:
     * concurrent or repeated requests with the same key all observe the same
     * code, registered exactly once. The cache lives as long as the code TTL.
     */
    public PluginModels.BindCodeResult create(UUID userId, String idempotencyKeyHash) {
        String idemKey = IDEMPOTENCY_PREFIX + userId + ":" + idempotencyKeyHash;
        String userIndexKey = USER_INDEX_PREFIX + userId;
        String code = generateCode();
        Instant expiresAt = clock.instant().plus(properties.getBindCodeTtl());
        long ttlMillis = properties.getBindCodeTtl().toMillis();

        PluginModels.BindCodeResult fresh = new PluginModels.BindCodeResult(
                code, expiresAt, properties.getBindCodeTtl().toSeconds()
        );

        String serialized = redis.execute(
                CREATE_SCRIPT,
                List.of(userIndexKey, idemKey),
                Long.toString(clock.instant().toEpochMilli()),
                Long.toString(expiresAt.toEpochMilli()),
                Integer.toString(properties.getMaxActiveBindCodes()),
                VALUE_PREFIX,
                code,
                userId.toString(),
                Long.toString(ttlMillis),
                serialize(fresh)
        );
        return deserialize(serialized);
    }

    /**
     * Consume a bind code exactly once. Empty means invalid, expired or used —
     * indistinguishable on purpose so codes cannot be probed. The user index
     * member and the attempt counter are removed in the same atomic step.
     */
    public Optional<UUID> consume(String code) {
        String valueKey = VALUE_PREFIX + code;
        String serialized = redis.execute(
                CONSUME_SCRIPT,
                List.of(valueKey, ATTEMPT_PREFIX + code),
                USER_INDEX_PREFIX,
                code
        );
        if (serialized == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(serialized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
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
