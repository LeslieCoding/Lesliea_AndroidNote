package com.leslie.colorosnote.core.ink;

/**
 * Catmull-Rom centerline refinement (v1.4.18).
 *
 * <p>Stroke outlines used to be plain polyline strips: sampled points joined
 * by straight chords, so every direction change rendered as a visible corner
 * and a drawn circle came out as a polygon (user feedback: "拐弯处折角，画圆
 * 周围有棱角"). This refiner subdivides each sampled segment of the
 * centerline with a <b>centripetal</b> Catmull-Rom spline so the outline
 * follows a smooth curve through the original sample points. Centripetal
 * (not uniform) parameterization is deliberate: real pen input is unevenly
 * spaced, and uniform CR forms cusps/loops at sharp reversals of clustered
 * points (measured on real stroke data: 162° micro-turns vs 72° with
 * centripetal); centripetal CR is cusp-free within segments.
 *
 * <p>Subdivision is curvature-adaptive: the per-segment count scales with the
 * direction change between the segment's two spline tangents, so perfectly
 * straight runs stay unsubdivided (performance unchanged, v1.4.10/1.4.11
 * latency work preserved) and only genuine bends get extra vertices.
 *
 * <p>Both render paths use this one implementation: the committed mesh
 * ({@link BrushEngine#computeMesh} via {@link #refine}) and the incremental
 * live outline ({@link LiveOutlineBuilder}, per committed segment via
 * {@link #subdivisions}/{@link #eval}). Knot normals everywhere stay the
 * central difference of the original sample points (the spline tangent at a
 * knot points essentially along that chord), so consecutive segments keep
 * sharing exact joint chords and live-vs-committed shapes match.
 *
 * <p>Pure JVM — no android.graphics imports.
 */
public final class SplineRefiner {

    /** Max sub-segments emitted per sampled segment (curved bends only). */
    public static final int MAX_SUBDIVISIONS = 192;
    /** Direction change per emitted sub-segment at 1 px/page-unit. */
    private static final float BASE_MAX_TURN_STEP = (float) Math.toRadians(6.0);
    /** Prevent pathological vertex growth at extreme debug zoom levels. */
    private static final float MIN_MAX_TURN_STEP = (float) Math.toRadians(0.4);
    /**
     * Maximum estimated spline sagitta in final device pixels. v2.0.2: 0.2px;
     * v2.1.0: 0.15 -> 0.12px with MAX_SUBDIVISIONS 128 -> 192. Commit-time
     * meshes (page/window at deep zoom) now keep curvature facets sub-pixel
     * deep zoom. Live latency is unaffected: live tessellation feeds this
     * at the LiveInkQualityPolicy cap (<=12x), and only the finished stroke
     * re-meshes at the full device scale.
     */
    private static final float MAX_SAGITTA_PX = 0.12f;
    /** Tangent lengths below this are degenerate (coincident controls). */
    private static final float EPS = 1e-9f;
    /** Segment lengths below this are never subdivided (dot duplicates). */
    private static final float MIN_SEGMENT_LEN2 = 1e-12f;

    private SplineRefiner() {
    }

    /**
     * Number of sub-segments for spline segment p1->p2 (controls p0..p3).
     * Scales with the angle between the two end tangents (t1 ∝ p2-p0,
     * t2 ∝ p3-p1); 1 for straight or degenerate segments.
     */
    public static int subdivisions(float x0, float y0, float x1, float y1,
                                   float x2, float y2, float x3, float y3) {
        return subdivisions(x0, y0, x1, y1, x2, y2, x3, y3, 1f);
    }

    /**
     * Scale-aware subdivision. A fixed six-degree polygon is fine near fit
     * zoom but its facets become visible when magnified tens of times. The
     * angular tolerance therefore tightens with the square root of the
     * device-pixel scale (the sagitta of a chord grows with scale).
     */
    public static int subdivisions(float x0, float y0, float x1, float y1,
                                   float x2, float y2, float x3, float y3,
                                   float pixelsPerPageUnit) {
        float segDx = x2 - x1;
        float segDy = y2 - y1;
        if (segDx * segDx + segDy * segDy < MIN_SEGMENT_LEN2) {
            return 1;
        }
        float t1x = x2 - x0;
        float t1y = y2 - y0;
        float t2x = x3 - x1;
        float t2y = y3 - y1;
        float l1 = (float) Math.hypot(t1x, t1y);
        float l2 = (float) Math.hypot(t2x, t2y);
        if (l1 < EPS || l2 < EPS) {
            return 1;
        }
        float cos = (t1x * t2x + t1y * t2y) / (l1 * l2);
        cos = Math.max(-1f, Math.min(1f, cos));
        float angle = (float) Math.acos(cos);
        float scale = sanitizeScale(pixelsPerPageUnit);
        float maxTurn = Math.max(MIN_MAX_TURN_STEP,
                BASE_MAX_TURN_STEP / (float) Math.sqrt(scale));
        int angularSegments = (int) Math.ceil(angle / maxTurn);

        // Angle alone is not enough for sparse input: a ten-degree bend over
        // a very long chord has the same angular subdivision as a tiny bend,
        // yet its screen-space arc error can be dozens of pixels at deep zoom.
        // Estimate the whole-arc sagitta from chord length and tangent angle;
        // splitting an arc into n pieces reduces sagitta approximately by n².
        float chordPx = (float) Math.sqrt(segDx * segDx + segDy * segDy) * scale;
        float halfAngle = angle * 0.5f;
        float sinHalf = (float) Math.sin(halfAngle);
        float sagittaPx = sinHalf > 1e-5f
                ? chordPx * (1f - (float) Math.cos(halfAngle)) / (2f * sinHalf)
                : 0f;
        int sagittaSegments = sagittaPx > MAX_SAGITTA_PX
                ? (int) Math.ceil(Math.sqrt(sagittaPx / MAX_SAGITTA_PX)) : 1;
        int n = Math.max(angularSegments, sagittaSegments);
        return Math.max(1, Math.min(MAX_SUBDIVISIONS, n));
    }

    /** Fraction of the segment parameter used for the numeric tangent. */
    private static final float TANGENT_H = 1e-3f;

    /**
     * Centripetal Catmull-Rom evaluation of segment p1->p2 at t in [0,1].
     * {@code out} receives {x, y, tx, ty} with (tx,ty) the unnormalized
     * spline tangent (numeric derivative; zero-length only for degenerate
     * controls).
     */
    public static void eval(float x0, float y0, float x1, float y1,
                            float x2, float y2, float x3, float y3,
                            float t, float[] out) {
        eval(x0, y0, x1, y1, x2, y2, x3, y3, t, out, new float[4]);
    }

    /**
     * Allocation-free variant: {@code scratch} (length 4) holds the two
     * tangent probe points.
     */
    public static void eval(float x0, float y0, float x1, float y1,
                            float x2, float y2, float x3, float y3,
                            float t, float[] out, float[] scratch) {
        float t0 = 0f;
        float t1 = t0 + dt(x0, y0, x1, y1);
        float t2 = t1 + dt(x1, y1, x2, y2);
        float t3 = t2 + dt(x2, y2, x3, y3);
        float span = t2 - t1;
        point(x0, y0, x1, y1, x2, y2, x3, y3, t0, t1, t2, t3,
                t1 + span * t, out, 0);
        float h = span * TANGENT_H;
        point(x0, y0, x1, y1, x2, y2, x3, y3, t0, t1, t2, t3,
                t1 + span * t - h, scratch, 0);
        point(x0, y0, x1, y1, x2, y2, x3, y3, t0, t1, t2, t3,
                t1 + span * t + h, scratch, 2);
        out[2] = (scratch[2] - scratch[0]) / (2f * h);
        out[3] = (scratch[3] - scratch[1]) / (2f * h);
    }

    /** Centripetal knot interval: sqrt(chord length), floored vs degeneracy. */
    private static float dt(float ax, float ay, float bx, float by) {
        float d = (float) Math.sqrt(Math.hypot(bx - ax, by - ay));
        return Math.max(d, 1e-4f);
    }

    /** Curve point at parameter tt (classic Barry–Goldman nested lerps). */
    private static void point(float x0, float y0, float x1, float y1,
                              float x2, float y2, float x3, float y3,
                              float t0, float t1, float t2, float t3,
                              float tt, float[] out, int o) {
        float a1x = lerpParam(x0, x1, t0, t1, tt);
        float a1y = lerpParam(y0, y1, t0, t1, tt);
        float a2x = lerpParam(x1, x2, t1, t2, tt);
        float a2y = lerpParam(y1, y2, t1, t2, tt);
        float a3x = lerpParam(x2, x3, t2, t3, tt);
        float a3y = lerpParam(y2, y3, t2, t3, tt);
        float b1x = lerpParam(a1x, a2x, t0, t2, tt);
        float b1y = lerpParam(a1y, a2y, t0, t2, tt);
        float b2x = lerpParam(a2x, a3x, t1, t3, tt);
        float b2y = lerpParam(a2y, a3y, t1, t3, tt);
        out[o] = lerpParam(b1x, b2x, t1, t2, tt);
        out[o + 1] = lerpParam(b1y, b2y, t1, t2, tt);
    }

    private static float lerpParam(float a, float b, float ta, float tb, float t) {
        float span = tb - ta;
        if (span < 1e-12f) {
            return a;
        }
        return ((tb - t) * a + (t - ta) * b) / span;
    }

    /** Refined centerline: positions, widths, opacities and unit normals. */
    public static final class Refined {
        public float[] center;   // flat xy
        public float[] widths;
        public float[] opacities;
        public float[] normals;  // flat nx,ny (unit, or 0 for degenerate)
        public int count;
    }

    /**
     * Refines the (welded) knot polyline into a smooth centerline. Knots are
     * passed through exactly; interior sub-points follow the spline. Normals
     * at knots are the central difference of the knots — identical to the
     * pre-refinement mesh behavior, so live and committed joints agree;
     * interior normals come from the spline tangent.
     */
    public static Refined refine(float[] center, float[] widths,
                                 float[] opacities, int n) {
        return refine(center, widths, opacities, n, 1f);
    }

    /** Scale-aware variant used by device-resolution page/window rendering. */
    public static Refined refine(float[] center, float[] widths,
                                 float[] opacities, int n,
                                 float pixelsPerPageUnit) {
        Refined r = new Refined();
        if (n == 0) {
            r.center = new float[0];
            r.widths = new float[0];
            r.opacities = new float[0];
            r.normals = new float[0];
            r.count = 0;
            return r;
        }
        // Pass 1: total refined point count.
        int total = 1;
        for (int i = 0; i < n - 1; i++) {
            total += subdivisions(
                    cx(center, i - 1, n), cy(center, i - 1, n),
                    cx(center, i, n), cy(center, i, n),
                    cx(center, i + 1, n), cy(center, i + 1, n),
                    cx(center, i + 2, n), cy(center, i + 2, n),
                    pixelsPerPageUnit);
        }
        r.center = new float[total * 2];
        r.widths = new float[total];
        r.opacities = new float[total];
        r.normals = new float[total * 2];
        r.count = total;

        float[] ev = new float[4];
        float[] evScratch = new float[4];
        int k = 0;
        for (int i = 0; i < n - 1; i++) {
            float x0 = cx(center, i - 1, n);
            float y0 = cy(center, i - 1, n);
            float x1 = cx(center, i, n);
            float y1 = cy(center, i, n);
            float x2 = cx(center, i + 1, n);
            float y2 = cy(center, i + 1, n);
            float x3 = cx(center, i + 2, n);
            float y3 = cy(center, i + 2, n);
            int sub = subdivisions(x0, y0, x1, y1, x2, y2, x3, y3,
                    pixelsPerPageUnit);
            for (int j = 0; j < sub; j++) {
                if (j == 0) {
                    // Knot: exact passthrough, knot central-difference normal.
                    r.center[k * 2] = x1;
                    r.center[k * 2 + 1] = y1;
                    r.widths[k] = widths[i];
                    r.opacities[k] = opacities[i];
                    knotNormal(center, i, n, r.normals, k);
                } else {
                    float t = (float) j / sub;
                    eval(x0, y0, x1, y1, x2, y2, x3, y3, t, ev, evScratch);
                    r.center[k * 2] = ev[0];
                    r.center[k * 2 + 1] = ev[1];
                    r.widths[k] = widths[i] + (widths[i + 1] - widths[i]) * t;
                    r.opacities[k] =
                            opacities[i] + (opacities[i + 1] - opacities[i]) * t;
                    tangentNormal(ev, r.normals, k);
                    if (r.normals[k * 2] == 0f && r.normals[k * 2 + 1] == 0f
                            && k > 0) {
                        // Degenerate tangent (coincident controls): reuse the
                        // previous normal so the outline never collapses.
                        r.normals[k * 2] = r.normals[(k - 1) * 2];
                        r.normals[k * 2 + 1] = r.normals[(k - 1) * 2 + 1];
                    }
                }
                k++;
            }
        }
        // Final knot.
        r.center[k * 2] = cx(center, n - 1, n);
        r.center[k * 2 + 1] = cy(center, n - 1, n);
        r.widths[k] = widths[n - 1];
        r.opacities[k] = opacities[n - 1];
        knotNormal(center, n - 1, n, r.normals, k);
        return r;
    }

    /** Central-difference normal at knot i (forward/backward at the ends),
     *  matching the pre-refinement mesh exactly. */
    private static void knotNormal(float[] center, int i, int n,
                                   float[] normals, int outIndex) {
        int prev = Math.max(0, i - 1);
        int next = Math.min(n - 1, i + 1);
        float dx = center[next * 2] - center[prev * 2];
        float dy = center[next * 2 + 1] - center[prev * 2 + 1];
        writeNormal(dx, dy, normals, outIndex, 0f, 0f);
    }

    /** Unit left normal of the spline tangent in {x,y,tx,ty} eval output. */
    private static void tangentNormal(float[] ev, float[] normals, int outIndex) {
        writeNormal(ev[2], ev[3], normals, outIndex, 0f, 0f);
    }

    private static void writeNormal(float dx, float dy, float[] normals,
                                    int outIndex, float fx, float fy) {
        float len = (float) Math.hypot(dx, dy);
        if (len > 1e-6f) {
            normals[outIndex * 2] = -dy / len;
            normals[outIndex * 2 + 1] = dx / len;
        } else {
            normals[outIndex * 2] = fx;
            normals[outIndex * 2 + 1] = fy;
        }
    }

    /** Knot x with end padding (index clamped into [0, n-1]). */
    private static float cx(float[] center, int i, int n) {
        return center[Math.min(n - 1, Math.max(0, i)) * 2];
    }

    private static float cy(float[] center, int i, int n) {
        return center[Math.min(n - 1, Math.max(0, i)) * 2 + 1];
    }

    private static float sanitizeScale(float scale) {
        return Float.isFinite(scale) && scale > 1f ? scale : 1f;
    }
}
