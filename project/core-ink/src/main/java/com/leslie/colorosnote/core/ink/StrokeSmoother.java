package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;

/**
 * Position/pressure smoothing and pull-string stabilization (requirement 7).
 *
 * <ul>
 *   <li>smoothing: 1€ (One Euro) adaptive low-pass filter (v1.1.8, replaced the
 *       fixed-alpha EMA). Cutoff slides with signal speed: slow handwriting is
 *       heavily denoised, fast flicks pass through with almost no lag.
 *       Parameters follow Chromium's Android input-stack tuning
 *       (dcutoff 1Hz, beta 0.01 in px — we use page units, see constants).
 *       BrushPreset.smoothing 0 = raw pass-through, 1 = heavy.</li>
 *   <li>stabilization: lazy pull-string mode; the emitted point only moves
 *       once the raw point leaves a radius around the anchor. Off by default
 *       (BrushPreset.stabilization == 0).</li>
 *   <li>pressure: separate 1€ filter (base 3.2Hz since v2.1.0) — pressure
 *       noise becomes width jitter, and pressure changes slowly, so it takes
 *       strong filtering with weak speed compensation; the base cutoff is
 *       still high enough that a deliberate press fattens the nib promptly.</li>
 * </ul>
 *
 * <p>Feed real MotionEvent timestamps (historical included) via
 * {@link #process(float, float, float, long, float[])}: the filter's alpha
 * depends on the true sample interval, and batched 240Hz stylus events must
 * not be treated as a fixed-rate stream.
 *
 * <p>Pure JVM; one instance per in-progress stroke (call {@link #reset()} on
 * stroke start).
 */
public final class StrokeSmoother {

    /** Max pull-string radius in page units at stabilization == 1. */
    private static final float MAX_STABILIZER_RADIUS = 24f;

    /**
     * v2.2.2 speed-adaptive stabilizer. A pull-string anchor trails the pen
     * by exactly its radius once the pen keeps moving — a CONSTANT SPATIAL
     * lag. At stabilization == 0.1 (fountain pen default) that is 2.4 page
     * units: invisible at fit zoom, but ~70 screen px at 1000% zoom, which
     * read as "the fountain pen can't keep up with fast cursive" (ballpoint
     * and pencil have stabilization == 0 and were fine). Fast writing is
     * exactly when stabilization matters least — hand jitter is dominated by
     * intentional motion — so the effective radius ramps from FULL at slow
     * speed to ZERO once the pen moves fast. Same strategy as the iPad note
     * apps' "writing assist follows speed" stabilizers.
     */
    private static final float STAB_FULL_SPEED = 150f;
    private static final float STAB_RETRACT_SPEED = 900f;
    /** EMA factor for the raw-speed estimate that drives the retract ramp. */
    private static final float STAB_SPEED_EMA = 0.35f;

    /**
     * Derivative low-pass cutoff. v2.0.0: 1Hz -> 2Hz — numeric simulation
     * (240Hz stream, sigma=0.5u jitter on 1.2Hz handwriting + 2500u/s ramp)
     * showed dc=2.0 with beta=0.07 cuts jitter RMS 1.69->1.46 (-13.7%) AND
     * steady-state lag 1.66->0.96ms (-42%): the derivative estimate follows
     * the pen sooner, so the speed-adaptive cutoff opens earlier in flicks.
     */
    private static final float DCUTOFF_HZ = 2.5f;
    /**
     * Speed coefficient for position (page-unit coords). Steady-state lag of a
     * 1€ filter tracking a ramp is ~1/(2π·β) units once β·v dwarfs fcmin:
     * 0.015 (Chromium's px-based 0.01 ported) trailed ~10.6 units (~30px at
     * fit zoom) behind the pen on fast strokes (v1.4.10 user feedback);
     * 0.05 cut that to ~3.2 units. v2.0.0: 0.05 -> 0.07 (with DCUTOFF_HZ=2),
     * another ~26% lag cut at 600 u/s (2.82 -> 2.08 units). v2.1.0: 0.085 ->
     * 0.09 — the shared 2D cutoff (v2.0.3) opens a little sooner as speed
     * rises, shaving more lag for the same jitter budget at the high end of
     * the smoothing slider.
     */
    private static final float POS_BETA = 0.09f;
    /** Speed coefficient for pressure (slow-changing signal: weak). */
    private static final float PRESSURE_BETA = 0.05f;
    /**
     * Pressure base cutoff: strong denoise, width stays smooth. v2.1.0:
     * 2.5 -> 3.2Hz — a deliberate press is a slow signal with a clean onset,
     * so raising the base cutoff makes the nib fatten sooner under a press
     * without letting per-sample pressure jitter reach the width (the
     * speed-compensation term stays weak on a slow-changing scalar).
     */
    private static final float PRESSURE_MINCUTOFF_HZ = 3.2f;
    /** smoothing slider 0 -> this position cutoff (near pass-through). */
    private static final float POS_FCMIN_AT_ZERO = 5.5f;
    /**
     * smoothing slider 1 -> this position cutoff (heavy denoise). v2.1.0:
     * 1.35 -> 2.2 — at 1.35 the position low-pass stalls near a dead stop and
     * the smoothed trace can lag a slow turn, then snap its direction as the
     * beta·speed term re-opens the cutoff (reads as a corner at max
     * "smoothing"). Raising the floor keeps the heavy-denoise character while
     * shrinking that stall, i.e. smoother AND more responsive at 100%.
     */
    private static final float POS_FCMIN_AT_ONE = 2.2f;
    /** Sample interval assumed when the caller has no real timestamps. */
    private static final float NOMINAL_TE_S = 1f / 120f;
    /** Smallest accepted sample interval (duplicate timestamps). */
    private static final float MIN_TE_S = 1e-4f;

    private boolean filterEnabled;
    private float stabilizerRadius = 0f;

    /**
     * X/Y must share one adaptive cutoff. Independent scalar 1€ filters open
     * at different rates when a turn transfers velocity from one axis to the
     * other; at high smoothing that phase mismatch straightens part of the
     * turn and then snaps its direction, which users perceive as a corner.
     */
    private final OneEuro2D filterPosition = new OneEuro2D();
    private final OneEuro filterPressure = new OneEuro();
    private final float[] positionOut = new float[2];

    private boolean hasOutput;
    private float outX;
    private float outY;
    private float outPressure;
    private float anchorX;
    private float anchorY;

    /** Raw-speed estimate (page units/s) driving the stabilizer retract. */
    private float rawSpeed;
    private float lastRawX;
    private float lastRawY;
    private long lastRawTs = -1L;

    /** Reads smoothing/stabilization parameters from the preset. */
    public void configure(BrushPreset preset) {
        float s = clamp01(preset.smoothing);
        filterEnabled = s > 0f;
        float posFcmin = POS_FCMIN_AT_ZERO + (POS_FCMIN_AT_ONE - POS_FCMIN_AT_ZERO) * s;
        filterPosition.configure(posFcmin, POS_BETA, DCUTOFF_HZ);
        filterPressure.configure(PRESSURE_MINCUTOFF_HZ, PRESSURE_BETA, DCUTOFF_HZ);
        stabilizerRadius = clamp01(preset.stabilization) * MAX_STABILIZER_RADIUS;
    }

    public void reset() {
        hasOutput = false;
        outPressure = 0f;
        filterPosition.reset();
        filterPressure.reset();
        rawSpeed = 0f;
        lastRawX = 0f;
        lastRawY = 0f;
        lastRawTs = -1L;
    }

    /**
     * Feeds one raw sample without a timestamp (assumes a nominal 120Hz
     * stream); returns the smoothed {x, y, pressure} in the provided (or a
     * new) array of length 3.
     */
    public float[] process(float x, float y, float pressure, float[] out) {
        return process(x, y, pressure, -1L, out);
    }

    /**
     * Feeds one raw sample with its real MotionEvent timestamp (millis, or a
     * negative value when unknown); returns the smoothed {x, y, pressure}.
     */
    public float[] process(float x, float y, float pressure, long timestampMs,
                           float[] out) {
        if (out == null || out.length < 3) {
            out = new float[3];
        }
        if (!hasOutput) {
            outX = x;
            outY = y;
            outPressure = pressure;
            anchorX = x;
            anchorY = y;
            filterPosition.seed(x, y, timestampMs);
            filterPressure.seed(pressure, timestampMs);
            lastRawX = x;
            lastRawY = y;
            lastRawTs = timestampMs;
            hasOutput = true;
        } else {
            float sx;
            float sy;
            if (filterEnabled) {
                filterPosition.filter(x, y, timestampMs, positionOut);
                sx = positionOut[0];
                sy = positionOut[1];
            } else {
                sx = x;
                sy = y;
            }
            // Pull-string stabilization on top of the filtered output.
            if (stabilizerRadius > 0f) {
                // v2.2.2: retract the radius as raw pen speed rises so fast
                // cursive keeps zero spatial lag (see STAB_FULL_SPEED doc).
                float dxRaw = x - lastRawX;
                float dyRaw = y - lastRawY;
                if (timestampMs > lastRawTs && (dxRaw != 0f || dyRaw != 0f)) {
                    float dt = (timestampMs - lastRawTs) / 1000f;
                    float inst = (float) Math.hypot(dxRaw, dyRaw) / dt;
                    rawSpeed += (inst - rawSpeed) * STAB_SPEED_EMA;
                }
                lastRawX = x;
                lastRawY = y;
                lastRawTs = timestampMs;
                float ramp = (STAB_RETRACT_SPEED - rawSpeed)
                        / (STAB_RETRACT_SPEED - STAB_FULL_SPEED);
                float radius = stabilizerRadius * clamp01(ramp);
                if (radius > 0f) {
                    float dx = sx - anchorX;
                    float dy = sy - anchorY;
                    float dist = (float) Math.hypot(dx, dy);
                    if (dist > radius) {
                        float k = (dist - radius) / dist;
                        anchorX += dx * k;
                        anchorY += dy * k;
                    }
                    sx = anchorX;
                    sy = anchorY;
                }
                // radius == 0: pass the filtered point straight through.
            }
            outX = sx;
            outY = sy;
            outPressure = filterEnabled
                    ? filterPressure.filter(pressure, timestampMs) : pressure;
        }
        out[0] = outX;
        out[1] = outY;
        out[2] = outPressure;
        return out;
    }

    /** Rotation-invariant 2D One Euro filter with a shared vector-speed cutoff. */
    private static final class OneEuro2D {
        private float mincutoff;
        private float beta;
        private float dcutoff;
        private float xHat;
        private float yHat;
        private float dxHat;
        private float dyHat;
        private float lastRawX;
        private float lastRawY;
        private float lastTimeS = -1f;
        private boolean seeded;

        void configure(float mincutoffHz, float betaValue, float dcutoffHz) {
            mincutoff = mincutoffHz;
            beta = betaValue;
            dcutoff = dcutoffHz;
        }

        void reset() {
            seeded = false;
            lastTimeS = -1f;
            xHat = 0f;
            yHat = 0f;
            dxHat = 0f;
            dyHat = 0f;
        }

        void seed(float x, float y, long timestampMs) {
            reset();
            seeded = true;
            xHat = x;
            yHat = y;
            lastRawX = x;
            lastRawY = y;
            if (timestampMs >= 0) {
                lastTimeS = timestampMs / 1000f;
            }
        }

        void filter(float x, float y, long timestampMs, float[] out2) {
            if (!seeded) {
                seed(x, y, timestampMs);
                out2[0] = x;
                out2[1] = y;
                return;
            }
            float timeS = timestampMs >= 0 ? timestampMs / 1000f : -1f;
            float te = NOMINAL_TE_S;
            if (timeS >= 0 && lastTimeS >= 0) {
                te = Math.max(MIN_TE_S, timeS - lastTimeS);
            }
            if (timeS >= 0) {
                lastTimeS = timeS;
            }
            float dx = (x - lastRawX) / te;
            float dy = (y - lastRawY) / te;
            lastRawX = x;
            lastRawY = y;
            float alphaD = OneEuro.alpha(dcutoff, te);
            dxHat += alphaD * (dx - dxHat);
            dyHat += alphaD * (dy - dyHat);
            float speed = (float) Math.hypot(dxHat, dyHat);
            float cutoff = mincutoff + beta * speed;
            float a = OneEuro.alpha(cutoff, te);
            xHat += a * (x - xHat);
            yHat += a * (y - yHat);
            out2[0] = xHat;
            out2[1] = yHat;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 1€ filter: first-order low-pass whose cutoff rises with the (low-passed)
     * signal derivative, so noise is crushed at rest and lag vanishes in
     * motion. Reference: Casiez et al., parameters per Chromium ui/base.
     */
    private static final class OneEuro {
        private float mincutoff;
        private float beta;
        private float dcutoff;
        private float xHat;
        private float dxHat;
        private float lastRaw;
        private float lastTimeS = -1f;
        private boolean seeded;

        void configure(float mincutoffHz, float betaValue, float dcutoffHz) {
            mincutoff = mincutoffHz;
            beta = betaValue;
            dcutoff = dcutoffHz;
        }

        void reset() {
            seeded = false;
            lastTimeS = -1f;
            xHat = 0f;
            dxHat = 0f;
        }

        void seed(float value, long timestampMs) {
            reset();
            seeded = true;
            xHat = value;
            lastRaw = value;
            if (timestampMs >= 0) {
                lastTimeS = timestampMs / 1000f;
            }
        }

        float filter(float value, long timestampMs) {
            if (!seeded) {
                seed(value, timestampMs);
                return value;
            }
            float timeS = timestampMs >= 0 ? timestampMs / 1000f : -1f;
            float te = NOMINAL_TE_S;
            if (timeS >= 0 && lastTimeS >= 0) {
                te = Math.max(MIN_TE_S, timeS - lastTimeS);
            }
            if (timeS >= 0) {
                lastTimeS = timeS;
            }
            float dx = (value - lastRaw) / te;
            lastRaw = value;
            float alphaD = alpha(dcutoff, te);
            dxHat += alphaD * (dx - dxHat);
            float cutoff = mincutoff + beta * Math.abs(dxHat);
            float a = alpha(cutoff, te);
            xHat += a * (value - xHat);
            return xHat;
        }

        static float alpha(float cutoffHz, float teS) {
            float tau = 1f / (2f * (float) Math.PI * cutoffHz);
            return 1f / (1f + tau / teS);
        }
    }
}
