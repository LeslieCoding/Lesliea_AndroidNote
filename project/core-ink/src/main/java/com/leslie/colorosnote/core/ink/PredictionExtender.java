package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.List;

/**
 * Extends the library prediction strip to a fixed total horizon (v1.4.11).
 *
 * <p>androidx.input's KalmanMotionEventPredictor (1.0.0) exposes no horizon
 * configuration and typically predicts only ~1 frame (~8-13ms) ahead — shorter
 * than the remaining touch-to-photon pipeline, so the translucent strip never
 * quite reaches the pen tip. This helper linearly extrapolates the library's
 * last predicted segment up to {@link #TARGET_HORIZON_MS} from the latest real
 * sample, capped in distance and disabled at low speed so stroke ends and
 * direction changes never overshoot visibly.
 *
 * <p>Pure JVM; the synthesized points are never committed to the stroke.
 */
public final class PredictionExtender {

    /** Total prediction horizon measured from the latest real sample. */
    static final long TARGET_HORIZON_MS = 25;
    /** Max extrapolation distance past the library prediction (page units). */
    static final float MAX_EXTENSION_UNITS = 14f;
    /** Below this speed extrapolation is skipped (near stop: only overshoots). */
    static final float MIN_SPEED_UNITS_PER_S = 120f;
    /** Spacing of synthesized extension samples. */
    static final long STEP_MS = 4;

    private PredictionExtender() {
    }

    /**
     * Appends extrapolated points to {@code predicted} until the strip covers
     * {@code lastRealTimeMs + TARGET_HORIZON_MS}; no-op when the library
     * prediction already reaches the horizon, when fewer than 2 predicted
     * points exist, or when the pen is nearly stationary.
     */
    public static void extend(List<StrokePoint> predicted, long lastRealTimeMs) {
        int n = predicted.size();
        if (n < 2) {
            return;
        }
        StrokePoint last = predicted.get(n - 1);
        long target = lastRealTimeMs + TARGET_HORIZON_MS;
        if (last.timestampMs >= target) {
            return;
        }
        StrokePoint prev = predicted.get(n - 2);
        float dtMs = (float) (last.timestampMs - prev.timestampMs);
        if (dtMs <= 0f) {
            return;
        }
        float vx = (last.x - prev.x) / dtMs; // page units per ms
        float vy = (last.y - prev.y) / dtMs;
        if (Math.hypot(vx, vy) * 1000f < MIN_SPEED_UNITS_PER_S) {
            return;
        }
        for (long t = last.timestampMs + STEP_MS; ; t += STEP_MS) {
            long ts = Math.min(t, target); // final point lands on the horizon
            float dx = vx * (ts - last.timestampMs);
            float dy = vy * (ts - last.timestampMs);
            if (Math.hypot(dx, dy) > MAX_EXTENSION_UNITS) {
                break;
            }
            StrokePoint p = new StrokePoint();
            p.x = last.x + dx;
            p.y = last.y + dy;
            p.pressure = last.pressure;
            p.timestampMs = ts;
            p.toolType = last.toolType;
            predicted.add(p);
            if (ts >= target) {
                break;
            }
        }
    }
}
