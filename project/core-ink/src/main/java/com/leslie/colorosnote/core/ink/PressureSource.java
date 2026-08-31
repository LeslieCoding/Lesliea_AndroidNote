package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

/**
 * Detects whether the input pipeline delivers real stylus pressure and
 * provides the velocity-based fallback when it does not (requirement 5.2).
 *
 * <p>Detection: the first {@link #DETECTION_SAMPLES} stylus pressure samples
 * are observed; if they all stay within {@link #CONSTANT_EPSILON} the hardware
 * is considered pressure-less (finger input is always treated as
 * pressure-less). The resulting {@link Mode} is queryable so the UI can show
 * "simulated pressure" instead of pretending real pressure exists.
 *
 * <p>Calibration: real pressure passes through an adaptive normalization
 * (requirement 5.2) that expands compressed hardware ranges so normal writing
 * produces visible width variation; a hard press always reaches the top.
 *
 * <p>Pure JVM; no Android imports so the detection logic is unit-testable.
 */
public final class PressureSource {

    public enum Mode {
        /** Not enough samples yet. */
        UNKNOWN,
        /** Hardware delivers varying pressure; use it normalized. */
        REAL,
        /** No hardware pressure; pressure is derived from velocity. */
        SIMULATED,
        /** No hardware pressure and simulation disabled; pressure stays 1. */
        UNAVAILABLE
    }

    /** Tool type constant mirrored from MotionEvent to stay JVM-only. */
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_STYLUS = 2;

    private static final int DETECTION_SAMPLES = 12;
    private static final float CONSTANT_EPSILON = 0.01f;
    /** Speed (page units/second) at which simulated pressure bottoms out. */
    private static final float SPEED_REF = 2000f;
    private static final float SIM_MIN = 0.2f;
    private static final float SIM_MAX = 1f;

    /**
     * Adaptive pressure calibration (requirement 5.2: 做设备校准). Many
     * styluses report a compressed range where normal writing never reaches
     * 1.0, which flattens the pressure response. The source tracks a slowly
     * decaying peak of recent real samples and normalizes against it, so
     * everyday pressure swings span most of [0,1]. A deliberate hard press
     * instantly re-raises the peak (max), so full force is never clipped
     * below what the hardware reports.
     */
    /** Peak never drops below this; bounds the amplification of weak sensors. */
    private static final float CALIBRATION_FLOOR = 0.3f;
    /** Per-sample peak decay (~2s half-life at stylus sample rates). */
    private static final float PEAK_DECAY = 0.997f;
    /** Calibrated value produced by a sample equal to the current peak. */
    private static final float PEAK_TARGET = 0.95f;

    private boolean simulationEnabled = true;
    private Mode mode = Mode.UNKNOWN;
    private int stylusSamples;
    private float minPressure = Float.MAX_VALUE;
    private float maxPressure = -Float.MAX_VALUE;
    /** Session-wide calibration peak; survives {@link #reset()} on purpose. */
    private float calibrationPeak = 1f;

    /** Whether velocity simulation may be used when real pressure is absent. */
    public void setSimulationEnabled(boolean enabled) {
        simulationEnabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    /** True while pressure values come from the velocity fallback. */
    public boolean isSimulated() {
        return mode == Mode.SIMULATED;
    }

    /** Resets detection; call when a new stroke begins. */
    public void reset() {
        mode = Mode.UNKNOWN;
        stylusSamples = 0;
        minPressure = Float.MAX_VALUE;
        maxPressure = -Float.MAX_VALUE;
    }

    /**
     * Feeds one raw sample. Finger samples never count as real pressure.
     * Detection locks in once enough stylus samples were seen.
     */
    public void onSample(StrokePoint p) {
        if (mode == Mode.REAL || mode == Mode.SIMULATED || mode == Mode.UNAVAILABLE) {
            return;
        }
        if (p.toolType == TOOL_TYPE_FINGER) {
            // Finger "pressure" on Android is touch-size derived, not real.
            stylusSamples++;
            observe(p.pressure);
            if (stylusSamples >= DETECTION_SAMPLES) {
                lockIn(false);
            }
            return;
        }
        if (p.toolType == TOOL_TYPE_STYLUS) {
            stylusSamples++;
            observe(p.pressure);
            if (stylusSamples >= DETECTION_SAMPLES) {
                lockIn(maxPressure - minPressure > CONSTANT_EPSILON);
            }
        }
    }

    /**
     * Returns the pressure to store on the point, given the current mode and
     * the pen speed (page units/second). Faster = thinner when simulating.
     * Real pressure is passed through the adaptive calibration curve so a
     * compressed hardware range still yields visible width variation.
     */
    public float resolvePressure(float rawPressure, float speed) {
        rawPressure = finiteOr(rawPressure, 0f);
        speed = Math.max(0f, finiteOr(speed, 0f));
        switch (mode) {
            case REAL:
                return calibrate(rawPressure);
            case SIMULATED:
                float t = Math.min(1f, speed / SPEED_REF);
                return SIM_MAX - (SIM_MAX - SIM_MIN) * t;
            case UNAVAILABLE:
                return clamp01(rawPressure);
            case UNKNOWN:
            default:
                // During detection, optimistic calibration keeps the first
                // stroke responsive; the mode locks in after a few samples.
                return calibrate(rawPressure);
        }
    }

    /** Forces the detected mode (e.g. after device calibration). */
    public void forceMode(Mode forced) {
        mode = forced;
    }

    /** Current calibration peak, exposed for diagnostics/settings UI. */
    public float getCalibrationPeak() {
        return calibrationPeak;
    }

    /** Resets adaptive calibration (e.g. user changed stylus or settings). */
    public void resetCalibration() {
        calibrationPeak = 1f;
    }

    private float calibrate(float raw) {
        float rawClamped = clamp01(raw);
        calibrationPeak = Math.max(rawClamped,
                Math.max(CALIBRATION_FLOOR, calibrationPeak * PEAK_DECAY));
        return clamp01(rawClamped * (PEAK_TARGET / calibrationPeak));
    }

    private void observe(float pressure) {
        pressure = finiteOr(pressure, 0f);
        minPressure = Math.min(minPressure, pressure);
        maxPressure = Math.max(maxPressure, pressure);
    }

    private void lockIn(boolean varying) {
        if (varying) {
            mode = Mode.REAL;
        } else {
            mode = simulationEnabled ? Mode.SIMULATED : Mode.UNAVAILABLE;
        }
    }

    private static float clamp01(float v) {
        if (!Float.isFinite(v)) {
            return 0f;
        }
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
