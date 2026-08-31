package com.leslie.colorosnote.core.ink;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the vertex weld to a device-pixel budget instead of a pure page-unit
 * one.
 *
 * <p>Both {@link LiveOutlineBuilder} and {@link BrushEngine}'s commit-time
 * mesh drop a sampled point when it lands closer than {@code 0.5 * width} to
 * the previous kept point. That threshold is in page units, but everything it
 * costs is perceived in screen pixels, so its damage scales with the zoom:
 *
 * <ul>
 *   <li>The newest kept point trails the pen by up to one weld distance. At
 *       fit zoom that is well under a pixel; at the 1000% cap the same
 *       0.7 page units is ~9 device pixels of visible tip lag — the ink
 *       stops keeping up with the hand.</li>
 *   <li>Detail finer than the weld distance is discarded outright. When the
 *       user zooms in to write something small, the strokes they draw are
 *       small *in page units*, so the weld quantises exactly the detail they
 *       zoomed in to draw.</li>
 * </ul>
 *
 * <p>Neither effect is a rip in the ink — {@code LiveInkDeepZoomCoverageTest}
 * shows the painted union is hole-free and edge-accurate — which is why they
 * survived the v2.1.x/v2.2.x tearing work.
 */
public class DeepZoomWeldResolutionTest {

    private static final float FIT_SCALE = 1.25f;
    private static final float MAX_ZOOM_SCALE = FIT_SCALE * 10f;
    private static final float PEN_WIDTH = 1.4f;

    /**
     * Tip lag must stay within a couple of device pixels at any zoom, so the
     * ink visually stays with the pen.
     */
    private static final float MAX_TIP_LAG_PX = 2.5f;

    /**
     * Live tessellation is fed a capped scale (LiveInkQualityPolicy in
     * core-render clamps it to ~8–10 for latency), while the ink is rasterized
     * at the true device scale. The weld clamp therefore sees the capped
     * value, so that is what this test hands the builder — measuring the lag
     * against the real scale the user actually looks at.
     */
    private static final float TESSELLATION_CAP = 10f;

    @Test
    public void tipLagStaysSubPixelishAtFitZoom() {
        float lag = tipLagPx(FIT_SCALE, FIT_SCALE);
        assertTrue("tip lag " + lag + " px at fit zoom", lag <= MAX_TIP_LAG_PX);
    }

    @Test
    public void tipLagStaysSubPixelishAtMaxZoom() {
        float lag = tipLagPx(TESSELLATION_CAP, MAX_ZOOM_SCALE);
        assertTrue("tip lag " + lag + " px at 1000% zoom (limit "
                + MAX_TIP_LAG_PX + ")", lag <= MAX_TIP_LAG_PX);
    }

    /**
     * The pixel-capped weld must not turn into a geometry explosion: at deep
     * zoom the extra knots have to be paid for by *less* subdivision per
     * segment, not by more total quads.
     */
    @Test
    public void deepZoomQuadBudgetStaysBounded() {
        int quads = quadsForArc(TESSELLATION_CAP, MAX_ZOOM_SCALE);
        assertTrue("live quads for one arc at 1000% zoom: " + quads,
                quads <= 600);
    }

    private static int quadsForArc(float builderScale, float deviceScale) {
        LiveOutlineBuilder builder = new LiveOutlineBuilder();
        builder.setPixelsPerPageUnit(builderScale);
        builder.reset();
        float radius = 12f;
        float step = 900f / deviceScale / 240f;
        float dTheta = step / radius;
        int samples = (int) Math.ceil(Math.toRadians(150.0) / dTheta);
        for (int i = 0; i < samples; i++) {
            float theta = i * dTheta;
            builder.addPoint(40f + radius * (float) Math.cos(theta),
                    40f + radius * (float) Math.sin(theta), PEN_WIDTH);
        }
        return builder.getQuadCount();
    }

    /**
     * A detail feature drawn while zoomed in must survive into the live
     * outline rather than being welded flat.
     */
    @Test
    public void fineDetailSurvivesTheWeldAtMaxZoom() {
        // A 2 page-unit tall zigzag: invisible at fit zoom, but 25 device
        // pixels tall at the 1000% cap — clearly something the user meant.
        float amplitude = 1.0f;
        LiveOutlineBuilder builder = new LiveOutlineBuilder();
        builder.setPixelsPerPageUnit(TESSELLATION_CAP);
        builder.reset();
        int kept = 0;
        float lastX = Float.NaN;
        float lastY = Float.NaN;
        float[] out = new float[3];
        for (int i = 0; i <= 40; i++) {
            float x = 40f + i * 0.25f;
            float y = 40f + ((i % 2 == 0) ? amplitude : -amplitude);
            builder.addPoint(x, y, PEN_WIDTH);
            if (builder.getLastPoint(out)
                    && (out[0] != lastX || out[1] != lastY)) {
                kept++;
                lastX = out[0];
                lastY = out[1];
            }
        }
        // Each zigzag vertex must survive; welding to the mid-line would keep
        // far fewer points and flatten the feature.
        assertTrue("only " + kept + " of 41 zigzag vertices survived the weld "
                + "at 1000% zoom", kept >= 30);
    }

    /** Peak distance between the newest raw sample and the newest kept one. */
    private static float tipLagPx(float builderScale, float deviceScale) {
        LiveOutlineBuilder builder = new LiveOutlineBuilder();
        builder.setPixelsPerPageUnit(builderScale);
        builder.reset();

        // Normal handwriting speed, sampled at 240 Hz, expressed in page
        // units for this zoom: the hand moves the same physical distance, so
        // the page-unit step shrinks as the zoom grows.
        float screenSpeedPxPerSec = 900f;
        float step = screenSpeedPxPerSec / deviceScale / 240f;

        float[] out = new float[3];
        float worst = 0f;
        for (int i = 0; i < 400; i++) {
            float x = 40f + i * step;
            float y = 40f;
            builder.addPoint(x, y, PEN_WIDTH);
            if (builder.getLastPoint(out)) {
                float lag = (float) Math.hypot(x - out[0], y - out[1]) * deviceScale;
                // Ignore the warm-up before the second point is accepted.
                if (i > 20) {
                    worst = Math.max(worst, lag);
                }
            }
        }
        return worst;
    }
}
