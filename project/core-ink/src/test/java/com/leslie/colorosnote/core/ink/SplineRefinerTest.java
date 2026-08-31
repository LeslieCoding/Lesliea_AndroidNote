package com.leslie.colorosnote.core.ink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Catmull-Rom centerline refinement (v1.4.18). */
public class SplineRefinerTest {

    private static final float EPS = 1e-4f;

    @Test
    public void collinearPointsAreNotSubdivided() {
        // Straight line: subdivision would waste vertices with zero visual
        // gain (performance guard).
        assertEquals(1, SplineRefiner.subdivisions(
                0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f));
    }

    @Test
    public void subdivisionCountScalesWithTurnAngle() {
        // 90 degree turn -> 15 six-degree sub-segments at normal scale.
        assertEquals(15, SplineRefiner.subdivisions(
                0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f));
        // ~11.4 degree tangent bend -> 2 sub-segments (6 degree step).
        // The spline tangent at p2 is t2 = p3 - p1, whose direction is half
        // the polyline's turn angle, so use a 22.8 degree corner here.
        double a = Math.toRadians(22.8);
        float x3 = 20f + 10f * (float) Math.cos(a);
        float y3 = 10f * (float) Math.sin(a);
        assertEquals(2, SplineRefiner.subdivisions(
                0f, 0f, 10f, 0f, 20f, 0f, x3, y3));
    }

    @Test
    public void deepZoomTightensAngularTolerance() {
        int normal = SplineRefiner.subdivisions(
                0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 1f);
        int deep = SplineRefiner.subdivisions(
                0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 64f);
        assertEquals(15, normal);
        assertTrue("64x zoom needs substantially denser curve geometry",
                deep >= normal * 6 && deep <= SplineRefiner.MAX_SUBDIVISIONS);
    }

    @Test
    public void deepZoomSparseLongCurveAlsoUsesPixelError() {
        // Same modest tangent turn, but a long chord magnified 64x. Angular
        // subdivision alone produces about 14 pieces; screen-space sagitta
        // requires materially more to avoid visible polygon facets.
        double a = Math.toRadians(20.0);
        float x3 = 200f + 100f * (float) Math.cos(a);
        float y3 = 100f * (float) Math.sin(a);
        int refined = SplineRefiner.subdivisions(
                0f, 0f, 100f, 0f, 200f, 0f, x3, y3, 64f);
        assertTrue("long sparse curve needs pixel-error refinement: " + refined,
                refined >= 25 && refined <= SplineRefiner.MAX_SUBDIVISIONS);
    }

    /**
     * v2.1.0: a deep-zoom square corner with long legs has a screen-space
     * sagitta big enough to saturate subdivision. MAX_SUBDIVISIONS was
     * raised 128 -> 192 so the committed mesh keeps 0.12px facets at 48x+.
     * Without the raise this call would cap at 128.
     */
    @Test
    public void deepZoomWideCornerRefinesBeyondOldCap() {
        int refined = SplineRefiner.subdivisions(
                0f, 0f, 1000f, 0f, 1000f, 1000f, 1000f, 2000f, 64f);
        assertTrue("deep-zoom wide corner should saturate the new cap, got "
                        + refined,
                refined > 128 && refined == SplineRefiner.MAX_SUBDIVISIONS);
    }

    @Test
    public void degenerateSegmentIsNotSubdivided() {
        // Coincident p1/p2 (the single-tap dot duplication).
        assertEquals(1, SplineRefiner.subdivisions(
                5f, 5f, 5f, 5f, 5f, 5f, 9f, 5f));
    }

    @Test
    public void evalPassesThroughSegmentEndpoints() {
        float[] out = new float[4];
        SplineRefiner.eval(0f, 5f, 10f, 0f, 20f, 10f, 30f, 5f, 0f, out);
        assertEquals(10f, out[0], EPS);
        assertEquals(0f, out[1], EPS);
        SplineRefiner.eval(0f, 5f, 10f, 0f, 20f, 10f, 30f, 5f, 1f, out);
        assertEquals(20f, out[0], EPS);
        assertEquals(10f, out[1], EPS);
    }

    @Test
    public void refinedCircleStaysOnCircle() {
        // 16 samples on a circle of radius 50: the polyline chords deviate
        // ~0.96 units from the true circle (sagitta); the refined spline must
        // sit much closer (no visible polygon facets). End segments get
        // clamped padding controls, so allow them a looser bound.
        int n = 16;
        float[] center = new float[n * 2];
        float[] widths = new float[n];
        float[] opacities = new float[n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            center[i * 2] = 100f + 50f * (float) Math.cos(a);
            center[i * 2 + 1] = 100f + 50f * (float) Math.sin(a);
            widths[i] = 2.5f;
            opacities[i] = 1f;
        }
        SplineRefiner.Refined r = SplineRefiner.refine(center, widths, opacities, n);
        assertTrue("curved stroke subdivided", r.count > n);
        float maxInterior = 0f;
        float maxAll = 0f;
        int k = 0;
        for (int seg = 0; seg < n - 1; seg++) {
            int i0 = Math.max(0, seg - 1);
            int i3 = Math.min(n - 1, seg + 2);
            int sub = SplineRefiner.subdivisions(
                    center[i0 * 2], center[i0 * 2 + 1],
                    center[seg * 2], center[seg * 2 + 1],
                    center[(seg + 1) * 2], center[(seg + 1) * 2 + 1],
                    center[i3 * 2], center[i3 * 2 + 1]);
            for (int j = 0; j < sub; j++, k++) {
                float dx = r.center[k * 2] - 100f;
                float dy = r.center[k * 2 + 1] - 100f;
                float dev = Math.abs((float) Math.hypot(dx, dy) - 50f);
                maxAll = Math.max(maxAll, dev);
                if (seg >= 1 && seg <= n - 3 && j > 0) {
                    maxInterior = Math.max(maxInterior, dev);
                }
            }
        }
        assertTrue("interior deviation " + maxInterior, maxInterior < 0.05f);
        assertTrue("end-segment deviation " + maxAll
                + " (polyline sagitta is 0.96)", maxAll < 0.75f);
    }

    @Test
    public void refineKeepsStraightPolylinesUntouched() {
        int n = 20;
        float[] center = new float[n * 2];
        float[] widths = new float[n];
        float[] opacities = new float[n];
        for (int i = 0; i < n; i++) {
            center[i * 2] = i * 5f;
            center[i * 2 + 1] = 7f;
            widths[i] = 2.5f;
            opacities[i] = 0.85f;
        }
        SplineRefiner.Refined r = SplineRefiner.refine(center, widths, opacities, n);
        assertEquals(n, r.count);
        for (int i = 0; i < n; i++) {
            assertEquals(center[i * 2], r.center[i * 2], 0f);
            assertEquals(center[i * 2 + 1], r.center[i * 2 + 1], 0f);
            assertEquals(2.5f, r.widths[i], 0f);
        }
    }

    @Test
    public void refineInterpolatesWidthsAlongCurve() {
        // Widths lerp along subdivided segments so tapered strokes stay
        // smooth (fountain pen flare must not become stepped).
        float[] center = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};
        float[] widths = {2f, 2f, 6f, 6f};
        float[] opacities = {1f, 1f, 1f, 1f};
        SplineRefiner.Refined r = SplineRefiner.refine(center, widths, opacities, 4);
        // The bent segment (10,0)->(10,10) subdivides; its interior widths
        // must lie strictly between 2 and 6 and stay monotonic.
        boolean sawInterior = false;
        float prev = 2f;
        for (int i = 0; i < r.count; i++) {
            float w = r.widths[i];
            assertTrue(w >= 2f - EPS && w <= 6f + EPS);
            assertTrue("width monotonic along stroke", w >= prev - EPS);
            if (w > 2f + EPS && w < 6f - EPS) {
                sawInterior = true;
            }
            prev = w;
        }
        assertTrue(sawInterior);
    }

    @Test
    public void knotNormalsMatchCentralDifference() {
        // Consistency contract with LiveOutlineBuilder: normals at the
        // original knots are perpendicular to (next - prev) of the knots.
        float[] center = {0f, 0f, 10f, 0f, 10f, 10f, 20f, 10f};
        float[] widths = {4f, 4f, 4f, 4f};
        float[] opacities = {1f, 1f, 1f, 1f};
        SplineRefiner.Refined r = SplineRefiner.refine(center, widths, opacities, 4);
        // Knot 1 (10,0): direction (10,10)-(0,0) -> normal (-1,1)/sqrt(2).
        // Find the refined index of knot 1 (segment 0 may subdivide).
        int sub0 = SplineRefiner.subdivisions(0f, 0f, 0f, 0f, 10f, 0f, 10f, 10f);
        int k1 = sub0; // knot 1 sits right after segment 0's sub-points
        double inv = 1.0 / Math.sqrt(2.0);
        assertEquals((float) -inv, r.normals[k1 * 2], EPS);
        assertEquals((float) inv, r.normals[k1 * 2 + 1], EPS);
        assertEquals(10f, r.center[k1 * 2], EPS);
        assertEquals(0f, r.center[k1 * 2 + 1], EPS);
    }
}
