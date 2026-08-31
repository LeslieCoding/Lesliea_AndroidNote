package com.leslie.colorosnote.core.render;

/**
 * Chooses the offscreen-bitmap resolution for completed strokes (v1.4.12).
 *
 * <p>Before v1.4.12 the completed-stroke layer was always baked at a fixed
 * 2x; zooming past 2x stretched that bitmap and ink turned to mush. The layer
 * is now re-baked at higher resolution as the user zooms in, bounded by:
 *
 * <ul>
 *   <li>{@link #MAX_PAGE_BYTES}: 40MB per page. An A4 page (595x842 units)
 *       costs ~2.0MB·scale², so this caps scale at ~4.58x. The cap must stay
 *       below the 48MB LRU budget: a bitmap larger than the whole budget
 *       would evict itself on insertion and thrash.
 *   </li>
 *   <li>{@link #TEXTURE_GUARD}: 8x keeps the bitmap under the 8192px GPU
 *       texture ceiling (A4 8x = 4762x6735). The memory cap binds first for
 *       A4; the guard protects larger custom page sizes.
 *   </li>
 * </ul>
 *
 * <p>Past the full-page cap the host switches to a viewport-window bitmap
 * (v1.4.13): only the visible page region (expanded {@link #WINDOW_MARGIN}
 * per side) is re-rendered at device resolution — pixels = viewPx·(1+2·margin),
 * independent of zoom, so the bitmap stays bounded (~27MB on a 1600x2560
 * screen) while ink is exactly as sharp as the screen at any zoom. The window
 * engages the moment the zoom passes the baked scale
 * ({@link #WINDOW_ENTER_FACTOR} = 1.0, v1.4.15): the former ×1.25 entry left
 * a blur band from the cap to 1.25x past it where the capped bitmap was
 * stretched with nothing sharper on top.
 *
 * <p>Pure JVM, unit-tested; no Android types.
 */
public final class RenderScalePolicy {

    /** Default bake resolution (page units -> bitmap px multiplier). */
    public static final float BASE_SCALE = 2f;
    /** Zoom factor over the baked scale that triggers a re-bake. */
    static final float TRIGGER_FACTOR = 1.25f;
    /** Bake slightly past the current zoom so a little more pinch stays sharp. */
    static final float HEADROOM = 1.15f;
    /** Per-page bitmap byte cap (must stay under the 48MB LRU budget). */
    static final long MAX_PAGE_BYTES = 40L * 1024 * 1024;
    /** Hard ceiling so bitmaps stay below the 8192px GPU texture limit. */
    static final float TEXTURE_GUARD = 8f;

    /** Per-side expansion of the viewport window, as a fraction of its size. */
    static final float WINDOW_MARGIN = 0.15f;
    /** Byte cap for the window bitmap (not part of the 48MB page LRU). */
    static final long MAX_WINDOW_BYTES = 32L * 1024 * 1024;
    /**
     * Window-mode entry: engage as soon as the zoom passes the baked scale
     * (v1.4.15). The old TRIGGER_FACTOR (1.25) entry left a blur band —
     * between the ~4.58x bake cap and 1.25x past it the capped bitmap was
     * stretched up to 25% with no sharper layer on top. Note this is NOT
     * TRIGGER_FACTOR: below the cap a re-bake still gets sharper, so a
     * slightly soft frame there self-heals on the debounced re-bake; past
     * the cap nothing else ever will, so the window must engage at once.
     */
    static final float WINDOW_ENTER_FACTOR = 1.0f;
    /**
     * Hysteresis: once active, window mode exits only when the zoom drops
     * below this factor of the baked scale. Must stay strictly below
     * {@link #WINDOW_ENTER_FACTOR}: an exit above entry would flap (enter on
     * one frame, discard on the next, re-enter...).
     */
    static final float WINDOW_EXIT_FACTOR = 0.98f;
    /** Re-render the window when the zoom drifted this far from its scale. */
    static final float WINDOW_SCALE_TOLERANCE = 0.05f;
    /** Containment slack so the window is re-rendered before going out of view. */
    static final float WINDOW_COVER_SLACK = 0.02f;

    private RenderScalePolicy() {
    }

    /** A viewport-window render target: page-space rect + bitmap pixel size. */
    public static final class WindowSpec {
        /** Covered page-space rect (clamped to the page). */
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        /** Bitmap pixels per page unit (== viewport scale unless byte-capped). */
        public final float scale;
        public final int pixelW;
        public final int pixelH;

        WindowSpec(float left, float top, float right, float bottom,
                   float scale, int pixelW, int pixelH) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.scale = scale;
            this.pixelW = pixelW;
            this.pixelH = pixelH;
        }
    }

    /** Bake resolution for the given viewport scale and page size. */
    public static float chooseBakeScale(float viewportScale, float pageW, float pageH) {
        float desired = Math.max(BASE_SCALE, viewportScale * HEADROOM);
        float memCap = (float) Math.sqrt(MAX_PAGE_BYTES / (4.0 * pageW * pageH));
        return Math.min(desired, Math.min(memCap, TEXTURE_GUARD));
    }

    /**
     * True when the baked layer is too coarse for the viewport: zoom exceeds
     * 1.25x the baked (or base) scale AND a re-bake would actually raise the
     * resolution (otherwise pages already at the cap would re-bake forever).
     */
    public static boolean needsRebake(float viewportScale, float bakedScale,
                                      float pageW, float pageH) {
        if (viewportScale <= Math.max(bakedScale, BASE_SCALE) * TRIGGER_FACTOR) {
            return false;
        }
        return chooseBakeScale(viewportScale, pageW, pageH) > bakedScale * 1.01f;
    }

    /**
     * Resolution for a page with no cached bitmap yet: BASE_SCALE at normal
     * zoom (page open / fit), zoom-adaptive when opened already zoomed in.
     */
    public static float initialScale(float viewportScale, float pageW, float pageH) {
        return needsRebake(viewportScale, BASE_SCALE, pageW, pageH)
                ? chooseBakeScale(viewportScale, pageW, pageH) : BASE_SCALE;
    }

    // ------------------------------------------------------------------
    // Viewport window (v1.4.13)
    // ------------------------------------------------------------------

    /**
     * True when the full-page layer is at its cap yet the zoom still exceeds
     * it, so the host should render the visible region into a window bitmap
     * at device resolution. Hysteresis: once active, stays active until the
     * zoom drops below WINDOW_EXIT_FACTOR of the baked scale (entry needs
     * WINDOW_ENTER_FACTOR = exactly 1x the baked scale), so hovering near
     * the threshold does not flap.
     */
    public static boolean shouldUseWindow(float viewportScale, float bakedScale,
                                          float pageW, float pageH,
                                          boolean windowActive) {
        float factor = windowActive ? WINDOW_EXIT_FACTOR : WINDOW_ENTER_FACTOR;
        if (viewportScale <= Math.max(bakedScale, BASE_SCALE) * factor) {
            return false;
        }
        // Only when a full-page re-bake cannot get sharper (cap reached);
        // otherwise the cheaper whole-page re-bake handles it.
        return chooseBakeScale(viewportScale, pageW, pageH) <= bakedScale * 1.01f;
    }

    /**
     * Window render target for the current view: the visible page rect
     * expanded by WINDOW_MARGIN per side and clamped to the page, at viewport
     * resolution. Pixels ≈ viewPx·(1+2·margin) regardless of zoom. If even
     * that exceeds {@link #MAX_WINDOW_BYTES} the margin is dropped, and as a
     * last resort the pixel scale is shrunk (slightly soft, bounded memory).
     */
    public static WindowSpec computeWindow(float viewportScale,
                                           float pageW, float pageH,
                                           float visL, float visT,
                                           float visR, float visB) {
        float mx = (visR - visL) * WINDOW_MARGIN;
        float my = (visB - visT) * WINDOW_MARGIN;
        float l = clamp(visL - mx, 0f, pageW);
        float t = clamp(visT - my, 0f, pageH);
        float r = clamp(visR + mx, 0f, pageW);
        float b = clamp(visB + my, 0f, pageH);
        if (r - l < 1e-3f || b - t < 1e-3f) {
            // Visible region misses the page: keep a minimal valid target.
            l = clamp(visL, 0f, pageW);
            t = clamp(visT, 0f, pageH);
            r = Math.min(pageW, l + 1f);
            b = Math.min(pageH, t + 1f);
        }
        int pw = Math.max(1, Math.round((r - l) * viewportScale));
        int ph = Math.max(1, Math.round((b - t) * viewportScale));
        float scale = viewportScale;
        if ((long) pw * ph * 4L > MAX_WINDOW_BYTES) {
            l = clamp(visL, 0f, pageW);
            t = clamp(visT, 0f, pageH);
            r = clamp(visR, 0f, pageW);
            b = clamp(visB, 0f, pageH);
            pw = Math.max(1, Math.round((r - l) * viewportScale));
            ph = Math.max(1, Math.round((b - t) * viewportScale));
            if ((long) pw * ph * 4L > MAX_WINDOW_BYTES) {
                float shrink = (float) Math.sqrt(
                        MAX_WINDOW_BYTES / (4.0 * pw * ph));
                pw = Math.max(1, (int) (pw * shrink));
                ph = Math.max(1, (int) (ph * shrink));
                scale = pw / (r - l);
            }
        }
        return new WindowSpec(l, t, r, b, scale, pw, ph);
    }

    /**
     * True when the window still covers the visible region at (nearly) the
     * current zoom, so no re-render is needed. The 2% slack makes the window
     * refresh *before* its edge enters the view; edges clamped to the page
     * border never report uncovered (the gray workspace holds no ink).
     */
    public static boolean windowCovers(WindowSpec w, float pageW, float pageH,
                                       float visL, float visT,
                                       float visR, float visB,
                                       float viewportScale) {
        if (Math.abs(viewportScale - w.scale) > w.scale * WINDOW_SCALE_TOLERANCE) {
            return false;
        }
        float vL = clamp(visL, 0f, pageW);
        float vT = clamp(visT, 0f, pageH);
        float vR = clamp(visR, 0f, pageW);
        float vB = clamp(visB, 0f, pageH);
        float sx = (w.right - w.left) * WINDOW_COVER_SLACK;
        float sy = (w.bottom - w.top) * WINDOW_COVER_SLACK;
        boolean okL = w.left <= 0f || vL >= w.left + sx;
        boolean okT = w.top <= 0f || vT >= w.top + sy;
        boolean okR = w.right >= pageW || vR <= w.right - sx;
        boolean okB = w.bottom >= pageH || vB <= w.bottom - sy;
        return okL && okT && okR && okB;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
