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
    /** Turn-rate clamp, so a noisy sample triple cannot spin up a spiral. */
    static final float MAX_TURN_RAD_PER_MS = 0.02f;
    /** Above this per-step turn the pen is cornering; shorten the horizon. */
    static final float SHARP_TURN_RAD_PER_STEP = 0.05f;

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
        // v3.0.1: follow the curve instead of shooting off along the tangent.
        // A straight extrapolation of up to MAX_EXTENSION_UNITS is invisible on
        // a straight stroke but very visible on a curved one: every frame paints
        // a straight spur several page units long at the pen tip, so drawing a
        // circle quickly showed up as a polygon of those spurs. Rotating the
        // velocity by the turn rate the pen is already exhibiting keeps the
        // predicted strip on the arc.
        float turnPerMs = estimateTurnPerMs(predicted, dtMs);
        // A hard corner is not predictable — halve the horizon rather than
        // guess a spiral through it.
        long horizon = target;
        if (Math.abs(turnPerMs) * STEP_MS > SHARP_TURN_RAD_PER_STEP) {
            horizon = last.timestampMs + (target - last.timestampMs) / 2L;
        }

        float px = last.x;
        float py = last.y;
        float travelled = 0f;
        long prevTs = last.timestampMs;
        for (long t = last.timestampMs + STEP_MS; ; t += STEP_MS) {
            long ts = Math.min(t, horizon); // final point lands on the horizon
            float step = ts - prevTs;
            if (step <= 0f) {
                break;
            }
            // Rotate the velocity by the turn accumulated over this step.
            float theta = turnPerMs * step;
            float cos = (float) Math.cos(theta);
            float sin = (float) Math.sin(theta);
            float rvx = vx * cos - vy * sin;
            float rvy = vx * sin + vy * cos;
            vx = rvx;
            vy = rvy;
            float dx = vx * step;
            float dy = vy * step;
            travelled += (float) Math.hypot(dx, dy);
            if (travelled > MAX_EXTENSION_UNITS) {
                break;
            }
            px += dx;
            py += dy;
            StrokePoint p = new StrokePoint();
            p.x = px;
            p.y = py;
            p.pressure = last.pressure;
            p.timestampMs = ts;
            p.toolType = last.toolType;
            predicted.add(p);
            prevTs = ts;
            if (ts >= horizon) {
                break;
            }
        }
    }

    /**
     * Signed turn rate (radians per ms) from the last three predicted samples,
     * clamped so a noisy triple cannot spin the extension into a spiral.
     * Returns 0 when there is not enough history — i.e. plain straight-line
     * extrapolation, the behaviour before v3.0.1.
     */
    private static float estimateTurnPerMs(List<StrokePoint> predicted, float dtMs) {
        int n = predicted.size();
        if (n < 3 || dtMs <= 0f) {
            return 0f;
        }
        StrokePoint c = predicted.get(n - 1);
        StrokePoint b = predicted.get(n - 2);
        StrokePoint a = predicted.get(n - 3);
        float ax = b.x - a.x;
        float ay = b.y - a.y;
        float bx = c.x - b.x;
        float by = c.y - b.y;
        if (Math.hypot(ax, ay) < 1e-4f || Math.hypot(bx, by) < 1e-4f) {
            return 0f;
        }
        float angle = (float) Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
        float perMs = angle / dtMs;
        return Math.max(-MAX_TURN_RAD_PER_MS, Math.min(MAX_TURN_RAD_PER_MS, perMs));
    }
}
