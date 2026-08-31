package com.leslie.colorosnote.core.storage.trash;

/**
 * Pure-function 15-day trash retention rules (requirement 25). Kept free of
 * Android/Room so the policy itself is covered by local JVM unit tests.
 */
public final class TrashRetentionPolicy {

    public static final long RETENTION_DAYS = 15;
    public static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final long RETENTION_MS = RETENTION_DAYS * DAY_MS;

    private TrashRetentionPolicy() {
    }

    /** Timestamp at which an item deleted at deletedAtMs becomes purgeable. */
    public static long expiresAtMs(long deletedAtMs) {
        return deletedAtMs + RETENTION_MS;
    }

    /** True once the retention period has fully elapsed (inclusive boundary). */
    public static boolean isExpired(long deletedAtMs, long nowMs) {
        return nowMs - deletedAtMs >= RETENTION_MS;
    }

    /**
     * Full days left before purging, for "剩余 N 天" display. 0 once expired.
     * Partial days round up (an item with 25h left shows 2 days).
     */
    public static long remainingDays(long deletedAtMs, long nowMs) {
        long remaining = expiresAtMs(deletedAtMs) - nowMs;
        if (remaining <= 0L) {
            return 0L;
        }
        return (remaining + DAY_MS - 1L) / DAY_MS;
    }

    /** Cutoff timestamp for a sweep: entries deleted at or before it are expired. */
    public static long expiryCutoffMs(long nowMs) {
        return nowMs - RETENTION_MS;
    }
}
