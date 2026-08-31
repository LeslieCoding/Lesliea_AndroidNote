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

    // v2.1.3: the viewport itself is capped at 10x the fitted scale
    // (ViewportTransform.MAX_ZOOM_FACTOR), so the extreme-zoom regime these
    // floors guarded against (30x+) can no longer occur. The fast-write
    // floor moves 6 -> 8: tessellation scale stays within 80% of the
    // viewport at full speed, which keeps chord error low enough that the
    // sagitta-cover discs never show, while per-frame quad counts stay
    // bounded (the live builder's per-segment subdivision cap is the
    // dominant latency control anyway).
    static final float MAX_IDLE_SCALE = 10f;
    static final float MIN_FAST_SCALE = 8f;
    private static final float FULL_QUALITY_SCREEN_SPEED = 300f;
    private static final float FAST_SCREEN_SPEED = 1800f;

    private LiveInkQualityPolicy() {
    }

    /**
     * Returns the scale used only for live spline tessellation. Velocity is in
     * page units/second and is converted to physical screen pixels/second.
     */
    public static float tessellationScale(float viewportScale,
                                          float velocityX, float velocityY) {
        float scale = finitePositive(viewportScale) ? viewportScale : 1f;
        float speedPage = (float) Math.hypot(velocityX, velocityY);
        float speedScreen = Float.isFinite(speedPage) ? speedPage * scale : 0f;
        float t = clamp01((speedScreen - FULL_QUALITY_SCREEN_SPEED)
                / (FAST_SCREEN_SPEED - FULL_QUALITY_SCREEN_SPEED));
        // Smoothstep avoids visible quality steps as writing speed changes.
        t = t * t * (3f - 2f * t);
        float cap = MAX_IDLE_SCALE + (MIN_FAST_SCALE - MAX_IDLE_SCALE) * t;
        return Math.max(1f, Math.min(scale, cap));
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0f;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
