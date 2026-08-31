package com.leslie.colorosnote.core.render;

import com.leslie.colorosnote.core.ink.LiveOutlineBuilder;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Measures, in device pixels, how much of a curved live stroke the live
 * geometry actually paints at deep zoom.
 *
 * <p>Screenshots cannot answer the "断层 / stroke looks chopped into blocks at
 * 1000%" report: {@code adb input swipe} only draws straight lines, and a
 * straight line never tears. So this test rasterizes the union the renderer
 * would draw (committed quads + sagitta-cover discs + the transient tail strip)
 * onto a pixel grid at the real device scale, rasterizes the ideal capsule band
 * around the true centerline at the same scale, and reports the difference.
 *
 * <p>Two distinct defects are separated:
 * <ul>
 *   <li><b>Interior holes</b> — uncovered pixels fully enclosed by painted
 *       ones. Those are true rips in the ink.</li>
 *   <li><b>Outer-edge deficit</b> — ideal-band pixels the union misses along
 *       the convex side of a bend. That is the crescent the cover discs exist
 *       to fill; measured here as a fraction of the band and as the deepest
 *       radial bite in pixels.</li>
 * </ul>
 *
 * <p>The geometry is fed at the tessellation scale {@link LiveInkQualityPolicy}
 * would pick, not at the viewport scale, because that gap is the thing under
 * test: live ink is tessellated at a capped scale and then rasterized at the
 * full one.
 */
public class LiveInkDeepZoomCoverageTest {

    /**
     * Fit scale on the OPPO Pad: a 595x842 page fitted into a ~1090 px tall
     * ink view. 1000% zoom is 10x this (ViewportTransform.MAX_ZOOM_FACTOR).
     */
    private static final float FIT_SCALE = 1.25f;
    private static final float DEVICE_SCALE = FIT_SCALE * 10f;

    /** 0.5 pt fountain pen, the user's configured width, in page units. */
    private static final float PEN_WIDTH = 1.4f;

    /**
     * Fast handwriting: ~1800 screen px/s is where LiveInkQualityPolicy has
     * fully crossed into its fast-write budget.
     */
    private static final float FAST_SCREEN_SPEED = 1800f;

    @Test
    public void fastCurveAtMaxZoomHasNoInteriorHoles() {
        Coverage c = renderArc(FAST_SCREEN_SPEED);
        assertTrue("live ink tore open at 1000% zoom: " + c.interiorHoles
                        + " enclosed uncovered pixels",
                c.interiorHoles == 0);
    }

    @Test
    public void fastCurveAtMaxZoomStaysWithinAPixelOfTheIdealBand() {
        Coverage c = renderArc(FAST_SCREEN_SPEED);
        // A polygonal band is inscribed in the true arc, so a sub-pixel
        // deficit on the convex side is expected and invisible. Anything
        // deeper than one device pixel reads as a scalloped / chopped edge.
        assertTrue("outer edge bitten " + c.maxDeficitDepthPx
                        + " px deep at 1000% zoom (fast write)",
                c.maxDeficitDepthPx <= 1.0f);
    }

    @Test
    public void slowCurveAtMaxZoomStaysWithinAPixelOfTheIdealBand() {
        Coverage c = renderArc(120f);
        assertTrue("outer edge bitten " + c.maxDeficitDepthPx
                        + " px deep at 1000% zoom (slow write)",
                c.maxDeficitDepthPx <= 1.0f);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final class Coverage {
        int interiorHoles;
        float maxDeficitDepthPx;
        float deficitFraction;

        @Override
        public String toString() {
            return "holes=" + interiorHoles
                    + " maxDeficitPx=" + maxDeficitDepthPx
                    + " deficitFraction=" + deficitFraction;
        }
    }

    /**
     * Builds a circular arc sampled the way a 240 Hz stylus samples it at the
     * given screen speed, feeds it through the live builder, and measures the
     * painted union against the ideal band.
     */
    private static Coverage renderArc(float screenSpeedPxPerSec) {
        // Arc radius chosen so the bend is a normal handwriting curve (a
        // lowercase 'e' loop is a few millimetres across on the page).
        final float radiusPu = 12f;
        final float centerX = 40f;
        final float centerY = 40f;

        float pageSpeed = screenSpeedPxPerSec / DEVICE_SCALE;
        float dtSec = 1f / 240f;
        float stepPu = pageSpeed * dtSec;
        float dTheta = stepPu / radiusPu;

        float tessScale = LiveInkQualityPolicy.tessellationScale(
                DEVICE_SCALE, FIT_SCALE, pageSpeed, 0f);

        LiveOutlineBuilder builder = new LiveOutlineBuilder();
        builder.setPixelsPerPageUnit(tessScale);
        builder.reset();

        // Sweep 150 degrees of arc.
        int samples = (int) Math.ceil(Math.toRadians(150.0) / dTheta);
        float[] cxs = new float[samples];
        float[] cys = new float[samples];
        for (int i = 0; i < samples; i++) {
            float theta = i * dTheta;
            float x = centerX + radiusPu * (float) Math.cos(theta);
            float y = centerY + radiusPu * (float) Math.sin(theta);
            cxs[i] = x;
            cys[i] = y;
            builder.addPoint(x, y, PEN_WIDTH);
        }

        return measure(builder, cxs, cys);
    }

    private static Coverage measure(LiveOutlineBuilder builder,
                                    float[] cxsAll, float[] cysAll) {
        // The builder welds samples closer than ~half a pen width, so the
        // newest accepted point trails the newest raw sample. That tip lag is
        // the weld's intended behaviour, not a rip, so the ideal band is
        // measured only up to the last accepted point — otherwise the harness
        // would score the weld as a defect and hide the thing being looked for.
        float[] last = new float[3];
        int end = cxsAll.length - 1;
        if (builder.getLastPoint(last)) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i < cxsAll.length; i++) {
                float d = (float) Math.hypot(cxsAll[i] - last[0], cysAll[i] - last[1]);
                if (d < best) {
                    best = d;
                    end = i;
                }
            }
        }
        float[] cxs = new float[end + 1];
        float[] cys = new float[end + 1];
        System.arraycopy(cxsAll, 0, cxs, 0, end + 1);
        System.arraycopy(cysAll, 0, cys, 0, end + 1);

        // Bounding box in page units, padded by a full pen width.
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < cxs.length; i++) {
            minX = Math.min(minX, cxs[i]);
            maxX = Math.max(maxX, cxs[i]);
            minY = Math.min(minY, cys[i]);
            maxY = Math.max(maxY, cys[i]);
        }
        float pad = PEN_WIDTH * 2f;
        minX -= pad; minY -= pad; maxX += pad; maxY += pad;

        int w = (int) Math.ceil((maxX - minX) * DEVICE_SCALE);
        int h = (int) Math.ceil((maxY - minY) * DEVICE_SCALE);

        boolean[] painted = new boolean[w * h];
        boolean[] ideal = new boolean[w * h];

        // --- ideal band: within half a pen width of the polyline centerline
        float half = PEN_WIDTH / 2f;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float x = minX + (px + 0.5f) / DEVICE_SCALE;
                float y = minY + (py + 0.5f) / DEVICE_SCALE;
                for (int i = 0; i + 1 < cxs.length; i++) {
                    if (distToSegment(x, y, cxs[i], cys[i], cxs[i + 1], cys[i + 1])
                            <= half) {
                        ideal[py * w + px] = true;
                        break;
                    }
                }
            }
        }

        // --- painted union: committed quads, cover discs, tail strip, tail discs
        float[] quads = builder.getQuads();
        for (int q = 0; q < builder.getQuadCount(); q++) {
            fillQuad(painted, w, h, minX, minY, quads, q * 8);
        }
        float[] tailQuads = builder.getTailQuads();
        for (int q = 0; q < builder.getTailQuadCount(); q++) {
            fillQuad(painted, w, h, minX, minY, tailQuads, q * 8);
        }
        fillDiscs(painted, w, h, minX, minY,
                builder.getMidpoints(), builder.getMidpointCount());
        fillDiscs(painted, w, h, minX, minY,
                builder.getTailDiscs(), builder.getTailDiscCount());
        // The renderer also closes both ends with a round cap sub-path, and
        // the ideal band includes those semicircles. Without them the harness
        // would report half a pen width of phantom deficit at the endpoints.
        float[] cap = new float[(256 + 1) * 2];
        fillPolygon(painted, w, h, minX, minY, cap, builder.headCap(cap));
        fillPolygon(painted, w, h, minX, minY, cap, builder.tailCap(cap));

        Coverage c = new Coverage();

        // --- deficit: ideal but not painted
        int idealCount = 0;
        int deficitCount = 0;
        float maxDepth = 0f;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int idx = py * w + px;
                if (!ideal[idx]) {
                    continue;
                }
                idealCount++;
                if (painted[idx]) {
                    continue;
                }
                deficitCount++;
                // Depth = distance from this missed pixel to the nearest
                // painted pixel, in device pixels. A one-pixel-wide rim is
                // anti-aliasing noise; a deep bite is a visible scallop.
                float depth = depthToPainted(painted, w, h, px, py);
                maxDepth = Math.max(maxDepth, depth);
            }
        }
        c.deficitFraction = idealCount == 0 ? 0f : (float) deficitCount / idealCount;
        c.maxDeficitDepthPx = maxDepth;
        c.interiorHoles = countEnclosedHoles(painted, w, h);
        return c;
    }

    /**
     * Distance in pixels from (px,py) to the closest painted pixel, searched
     * outward in rings and capped — only used to size a defect, not to find
     * one.
     */
    private static float depthToPainted(boolean[] painted, int w, int h,
                                        int px, int py) {
        for (int r = 1; r <= 24; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int x = px + dx;
                    int y = py + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        continue;
                    }
                    if (painted[y * w + x]) {
                        return r;
                    }
                }
            }
        }
        return 24f;
    }

    /**
     * Counts uncovered pixels that cannot reach the border through uncovered
     * pixels — i.e. holes fully enclosed by ink.
     */
    private static int countEnclosedHoles(boolean[] painted, int w, int h) {
        boolean[] reachable = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        for (int px = 0; px < w; px++) {
            sp = push(stack, sp, painted, reachable, w, h, px, 0);
            sp = push(stack, sp, painted, reachable, w, h, px, h - 1);
        }
        for (int py = 0; py < h; py++) {
            sp = push(stack, sp, painted, reachable, w, h, 0, py);
            sp = push(stack, sp, painted, reachable, w, h, w - 1, py);
        }
        while (sp > 0) {
            int idx = stack[--sp];
            int px = idx % w;
            int py = idx / w;
            sp = push(stack, sp, painted, reachable, w, h, px - 1, py);
            sp = push(stack, sp, painted, reachable, w, h, px + 1, py);
            sp = push(stack, sp, painted, reachable, w, h, px, py - 1);
            sp = push(stack, sp, painted, reachable, w, h, px, py + 1);
        }
        int holes = 0;
        for (int i = 0; i < painted.length; i++) {
            if (!painted[i] && !reachable[i]) {
                holes++;
            }
        }
        return holes;
    }

    private static int push(int[] stack, int sp, boolean[] painted,
                            boolean[] reachable, int w, int h, int px, int py) {
        if (px < 0 || py < 0 || px >= w || py >= h) {
            return sp;
        }
        int idx = py * w + px;
        if (painted[idx] || reachable[idx]) {
            return sp;
        }
        reachable[idx] = true;
        stack[sp] = idx;
        return sp + 1;
    }

    private static void fillDiscs(boolean[] painted, int w, int h,
                                  float minX, float minY,
                                  float[] discs, int count) {
        for (int i = 0; i < count; i++) {
            float cx = discs[i * 3];
            float cy = discs[i * 3 + 1];
            float r = discs[i * 3 + 2] / 2f;
            int x0 = (int) Math.floor((cx - r - minX) * DEVICE_SCALE);
            int x1 = (int) Math.ceil((cx + r - minX) * DEVICE_SCALE);
            int y0 = (int) Math.floor((cy - r - minY) * DEVICE_SCALE);
            int y1 = (int) Math.ceil((cy + r - minY) * DEVICE_SCALE);
            for (int py = Math.max(0, y0); py < Math.min(h, y1); py++) {
                for (int px = Math.max(0, x0); px < Math.min(w, x1); px++) {
                    float x = minX + (px + 0.5f) / DEVICE_SCALE;
                    float y = minY + (py + 0.5f) / DEVICE_SCALE;
                    if (Math.hypot(x - cx, y - cy) <= r) {
                        painted[py * w + px] = true;
                    }
                }
            }
        }
    }

    /** Rasterizes a closed polygon given as {@code pairs} xy pairs. */
    private static void fillPolygon(boolean[] painted, int w, int h,
                                    float minX, float minY,
                                    float[] pts, int pairs) {
        if (pairs < 3) {
            return;
        }
        float[] xs = new float[pairs];
        float[] ys = new float[pairs];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float lyo = Float.MAX_VALUE, hyo = -Float.MAX_VALUE;
        for (int i = 0; i < pairs; i++) {
            xs[i] = pts[i * 2];
            ys[i] = pts[i * 2 + 1];
            lo = Math.min(lo, xs[i]);
            hi = Math.max(hi, xs[i]);
            lyo = Math.min(lyo, ys[i]);
            hyo = Math.max(hyo, ys[i]);
        }
        int x0 = (int) Math.floor((lo - minX) * DEVICE_SCALE);
        int x1 = (int) Math.ceil((hi - minX) * DEVICE_SCALE);
        int y0 = (int) Math.floor((lyo - minY) * DEVICE_SCALE);
        int y1 = (int) Math.ceil((hyo - minY) * DEVICE_SCALE);
        for (int py = Math.max(0, y0); py < Math.min(h, y1); py++) {
            for (int px = Math.max(0, x0); px < Math.min(w, x1); px++) {
                float x = minX + (px + 0.5f) / DEVICE_SCALE;
                float y = minY + (py + 0.5f) / DEVICE_SCALE;
                if (pointInPolygon(x, y, xs, ys)) {
                    painted[py * w + px] = true;
                }
            }
        }
    }

    /** Rasterizes the convex quad {l0, l1, r1, r0} stored flat at offset o. */
    private static void fillQuad(boolean[] painted, int w, int h,
                                 float minX, float minY, float[] q, int o) {
        float[] xs = {q[o], q[o + 2], q[o + 4], q[o + 6]};
        float[] ys = {q[o + 1], q[o + 3], q[o + 5], q[o + 7]};
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float lyo = Float.MAX_VALUE, hyo = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            lo = Math.min(lo, xs[i]);
            hi = Math.max(hi, xs[i]);
            lyo = Math.min(lyo, ys[i]);
            hyo = Math.max(hyo, ys[i]);
        }
        int x0 = (int) Math.floor((lo - minX) * DEVICE_SCALE);
        int x1 = (int) Math.ceil((hi - minX) * DEVICE_SCALE);
        int y0 = (int) Math.floor((lyo - minY) * DEVICE_SCALE);
        int y1 = (int) Math.ceil((hyo - minY) * DEVICE_SCALE);
        for (int py = Math.max(0, y0); py < Math.min(h, y1); py++) {
            for (int px = Math.max(0, x0); px < Math.min(w, x1); px++) {
                float x = minX + (px + 0.5f) / DEVICE_SCALE;
                float y = minY + (py + 0.5f) / DEVICE_SCALE;
                if (pointInPolygon(x, y, xs, ys)) {
                    painted[py * w + px] = true;
                }
            }
        }
    }

    private static boolean pointInPolygon(float x, float y, float[] xs, float[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean straddles = (ys[i] > y) != (ys[j] > y);
            if (straddles) {
                float xCross = xs[i] + (y - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i]);
                if (x < xCross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static float distToSegment(float px, float py,
                                       float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-12f) {
            return (float) Math.hypot(px - ax, py - ay);
        }
        float t = ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
