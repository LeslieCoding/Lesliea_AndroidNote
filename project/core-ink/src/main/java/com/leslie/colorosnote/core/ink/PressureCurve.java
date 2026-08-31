package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;

import java.util.List;

/**
 * Pressure response curves (requirement 6.1). Maps normalized raw pressure
 * [0,1] to a shaped [0,1] factor that the {@link BrushEngine} feeds into the
 * preset's min/max width range.
 *
 * <p>Design goals from the spec: light pressure still produces output, the
 * mid range changes clearly, the high range saturates gradually without
 * sudden width jumps. Pure JVM, no allocation in {@link #eval}.
 */
public final class PressureCurve {

    /** Floor so that a light touch always leaves a visible mark.
     *  v2.0.0: raised 0.05 -> 0.12 (anti ink-dropout at feather touch). */
    private static final float MIN_OUTPUT = 0.12f;

    private PressureCurve() {
    }

    /** Evaluates one of the built-in curves (CUSTOM falls back to BALANCED). */
    public static float eval(BrushPreset.PressureCurve type, float pressure) {
        float p = clamp01(pressure);
        float raw;
        switch (type) {
            case SOFT:
                // Concave: responsive at the light end. v2.0.0: exponent
                // 0.55 -> 0.75 (industry gamma band 0.7-0.8) — light strokes
                // stay finer and the mid range is more controllable; 0.55
                // inflated to half the width range already at p=0.3.
                raw = (float) Math.pow(p, 0.75f);
                break;
            case FIRM:
                // Convex: needs deliberate force before width grows.
                raw = (float) Math.pow(p, 1.8f);
                break;
            case BALANCED:
            case CUSTOM: // caller error; CUSTOM must go through evalCustom
            default:
                // Half linear, half smoothstep: mild S-curve, no dead zones.
                raw = 0.5f * p + 0.5f * smoothstep(p);
                break;
        }
        return MIN_OUTPUT + (1f - MIN_OUTPUT) * raw;
    }

    /**
     * Evaluates the preset's configured curve, including CUSTOM with 4..6
     * control points. Invalid custom data falls back to BALANCED.
     */
    public static float evalForPreset(BrushPreset preset, float pressure) {
        if (preset.pressureCurve == BrushPreset.PressureCurve.CUSTOM
                && preset.hasValidCustomCurve()) {
            return evalCustom(preset.customCurvePoints, pressure);
        }
        return eval(preset.pressureCurve, pressure);
    }

    /**
     * Monotonic piecewise-linear interpolation through custom control points.
     *
     * @param points flat {x0,y0,x1,y1,...} pairs, 4..6 points, x/y in [0,1];
     *               points are sorted by x and y is clamped non-decreasing so
     *               the result stays monotonic regardless of input order.
     */
    public static float evalCustom(List<Float> points, float pressure) {
        int n = points.size() / 2;
        if (n < BrushPreset.MIN_CUSTOM_CURVE_POINTS || n > BrushPreset.MAX_CUSTOM_CURVE_POINTS) {
            return eval(BrushPreset.PressureCurve.BALANCED, pressure);
        }
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = clamp01(points.get(i * 2));
            ys[i] = clamp01(points.get(i * 2 + 1));
        }
        // Insertion sort by x (n <= 6).
        for (int i = 1; i < n; i++) {
            float x = xs[i];
            float y = ys[i];
            int j = i - 1;
            while (j >= 0 && xs[j] > x) {
                xs[j + 1] = xs[j];
                ys[j + 1] = ys[j];
                j--;
            }
            xs[j + 1] = x;
            ys[j + 1] = y;
        }
        // Enforce monotonic non-decreasing output.
        for (int i = 1; i < n; i++) {
            if (ys[i] < ys[i - 1]) {
                ys[i] = ys[i - 1];
            }
        }
        float p = clamp01(pressure);
        if (p <= xs[0]) {
            return ys[0];
        }
        if (p >= xs[n - 1]) {
            return ys[n - 1];
        }
        for (int i = 1; i < n; i++) {
            if (p <= xs[i]) {
                float span = xs[i] - xs[i - 1];
                float t = span <= 1e-6f ? 0f : (p - xs[i - 1]) / span;
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[n - 1];
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
