package com.getjobs.cloud.delivery;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical delivery task status vocabulary (P8): the same eight values are
 * the persistent statuses (V10 CHECK) and the user-facing names surfacing
 * through every DTO. Legacy persistent names from before V10 still resolve in
 * filters and map to their canonical successor, so old clients and old rows
 * never break; no backend-only execution state exists anymore.
 */
public enum P6TaskStatus {
    WAITING_CONFIRM("待确认"),
    CONFIRMED("已确认"),
    PULLED_BY_PLUGIN("已领取"),
    RUNNING("执行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    SKIPPED("已跳过"),
    PAUSED_NEED_USER("需用户处理");

    private final String label;

    P6TaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Canonical statuses pass through unchanged; legacy pre-V10 names map to
     * their canonical successor. Unknown values pass through unchanged, never
     * null.
     */
    public static String fromPersistent(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "PENDING_CONFIRMATION" -> WAITING_CONFIRM.name();
            case "LEASED" -> PULLED_BY_PLUGIN.name();
            case "EXECUTING" -> RUNNING.name();
            case "SUCCEEDED" -> SUCCESS.name();
            case "PAUSED" -> PAUSED_NEED_USER.name();
            case "CANCELLED" -> SKIPPED.name();
            default -> status;
        };
    }

    /** Canonical name -> the single persistent status it matches (itself). */
    private static final Map<String, Set<String>> FILTER_STATUSES = Map.of(
            "WAITING_CONFIRM", Set.of("WAITING_CONFIRM"),
            "CONFIRMED", Set.of("CONFIRMED"),
            "PULLED_BY_PLUGIN", Set.of("PULLED_BY_PLUGIN"),
            "RUNNING", Set.of("RUNNING"),
            "SUCCESS", Set.of("SUCCESS"),
            "FAILED", Set.of("FAILED"),
            "SKIPPED", Set.of("SKIPPED"),
            "PAUSED_NEED_USER", Set.of("PAUSED_NEED_USER")
    );

    /** Legacy pre-V10 persistent names and their canonical successors. */
    private static final Map<String, String> LEGACY_STATUSES = Map.of(
            "PENDING_CONFIRMATION", "WAITING_CONFIRM",
            "LEASED", "PULLED_BY_PLUGIN",
            "EXECUTING", "RUNNING",
            "SUCCEEDED", "SUCCESS",
            "PAUSED", "PAUSED_NEED_USER",
            "CANCELLED", "SKIPPED"
    );

    /**
     * Resolves one client filter value (canonical name or legacy persistent
     * name) to the set of persistent statuses it matches. Null means the value
     * is not a known status and the caller should answer a validation error.
     */
    public static Set<String> persistentFilterStatuses(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        Set<String> canonical = FILTER_STATUSES.get(normalized);
        if (canonical != null) {
            return canonical;
        }
        String successor = LEGACY_STATUSES.get(normalized);
        if (successor != null) {
            return Set.of(successor);
        }
        return null;
    }

    /** Whether the client filter value is a known canonical name. */
    public static boolean isP6Name(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return FILTER_STATUSES.containsKey(normalized);
    }
}
