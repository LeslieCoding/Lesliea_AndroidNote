package com.leslie.colorosnote.core.render;

/**
 * Keeps in-progress ink inside a predictable per-sample geometry budget.
 *
 * <p>The completed stroke is still re-meshed at the full final canvas scale.
 * Live ink is transient and latency-sensitive: feeding an extreme viewport
 * scale directly into curvature tessellation can expand one hardware sample
 * into 100+ quads, then mutate and re-rasterize a huge Path every frame.
 */
public final class LiveInkQualityPolicy {

    /**
     * v2.5.0: these budgets are expressed as *zoom levels*, not absolute
     * scales.
     *
     * <p>The v2.1.3 reasoning was "the viewport is capped at 10x the fitted
     * scale, so 10 and 8 leave tessellation within 80% of the viewport at full
     * speed". That argument silently mixed two different quantities: the zoom
     * factor is relative to the fit scale, but the constants were compared
     * against the *absolute* page-units-to-pixels scale. On the OPPO Pad an
     * A4 page fits at ~1.25 px/page-unit, so the 1000% cap is an absolute
     * scale of 12.5 and the fast-write floor of 8 was really 64% of the
     * viewport, not 80% — and on a denser screen or a smaller page it would be
     * worse still.
     *
     * <p>Dividing by the fit scale restores the intended meaning on every
     * device: idle tessellation tracks the viewport all the way to the zoom
     * cap, and fast writing backs off to 80% of it.
     */
    static final float MAX_IDLE_ZOOM = 10f;
    static final float MIN_FAST_ZOOM = 8f;
    private static final float FULL_QUALITY_SCREEN_SPEED = 300f;
    private static final float FAST_SCREEN_SPEED = 1800f;

    private LiveInkQualityPolicy() {
    }

    /**
     * Returns the scale used only for live spline tessellation. Velocity is in
     * page units/second and is converted to physical screen pixels/second.
     *
     * @param viewportScale current page-units-to-device-pixels scale
     * @param fitScale      scale at which the page fits the viewport (the
     *                      "100%" zoom level); pass the viewport scale itself
     *                      when unknown, which reproduces a 1x baseline
     */
    public static float tessellationScale(float viewportScale, float fitScale,
                                          float velocityX, float velocityY) {
        float scale = finitePositive(viewportScale) ? viewportScale : 1f;
        float fit = finitePositive(fitScale) ? fitScale : 1f;
        float speedPage = (float) Math.hypot(velocityX, velocityY);
        float speedScreen = Float.isFinite(speedPage) ? speedPage * scale : 0f;
        float t = clamp01((speedScreen - FULL_QUALITY_SCREEN_SPEED)
                / (FAST_SCREEN_SPEED - FULL_QUALITY_SCREEN_SPEED));
        // Smoothstep avoids visible quality steps as writing speed changes.
        t = t * t * (3f - 2f * t);
        float zoomCap = MAX_IDLE_ZOOM + (MIN_FAST_ZOOM - MAX_IDLE_ZOOM) * t;
        return Math.max(1f, Math.min(scale, fit * zoomCap));
    }

    /** Legacy overload: treats the given scale as if the page fit at 1x. */
    public static float tessellationScale(float viewportScale,
                                          float velocityX, float velocityY) {
        return tessellationScale(viewportScale, 1f, velocityX, velocityY);
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0f;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
