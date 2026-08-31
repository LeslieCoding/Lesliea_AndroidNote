package com.leslie.colorosnote.core.common;

import java.util.ArrayList;
import java.util.List;

/**
 * App-wide registry of in-memory caches that can shrink under memory pressure.
 *
 * <p>Motivation (see docs/PERFORMANCE_RESEARCH.md): Android 14+ no longer
 * reliably delivers TRIM_MEMORY_RUNNING_CRITICAL, so every cache owner
 * registers a {@link TrimTarget} here; {@code InkNoteApp.onTrimMemory}
 * translates the callback level into a keep-fraction and calls
 * {@link #trimAll(float)}. Cache implementations stay Android-free and
 * unit-testable; only the registration call sites touch this class.
 */
public final class CacheTrimRegistry {

    /** Shrinks the cache so at most {@code keepFraction} of its budget stays in use. */
    public interface TrimTarget {
        void trimToFraction(float keepFraction);
    }

    private static final List<TrimTarget> TARGETS = new ArrayList<>();

    private CacheTrimRegistry() {
    }

    public static synchronized void register(TrimTarget target) {
        if (target != null && !TARGETS.contains(target)) {
            TARGETS.add(target);
        }
    }

    public static synchronized void unregister(TrimTarget target) {
        TARGETS.remove(target);
    }

    /** Trims every registered cache. {@code keepFraction} is clamped to [0, 1]. */
    public static synchronized void trimAll(float keepFraction) {
        float f = Math.max(0f, Math.min(1f, keepFraction));
        for (TrimTarget target : new ArrayList<>(TARGETS)) {
            try {
                target.trimToFraction(f);
            } catch (RuntimeException e) {
                // One bad target must not block the rest. No logging here:
                // this class stays Android-free so it is JVM-unit-testable.
            }
        }
    }

    /** Test hook: number of registered targets. */
    public static synchronized int size() {
        return TARGETS.size();
    }
}
