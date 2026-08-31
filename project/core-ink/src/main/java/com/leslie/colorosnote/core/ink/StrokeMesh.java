package com.leslie.colorosnote.core.ink;

/**
 * Variable-width polygon strip produced by {@link BrushEngine}: the meshed
 * form of a stroke, ready for rendering. All coordinates are logical page
 * units. Pure JVM; the Android drawing shell (core-render) converts this to
 * a Path/vertices without any drawPath-per-segment hacks.
 */
public final class StrokeMesh {

    /**
     * Segments used to tessellate the round caps at stroke start/end.
     * v1.4.17: 6 -> 24. At 6 the cap is a hexagon whose foremost element is
     * a vertex exactly on the stroke direction (150° interior angle), which
     * reads as a pointed/angular tip once the cap is more than a few px wide
     * (zoomed view or a wider custom width); 24 keeps the sagitta under
     * ~0.2% of the radius — indistinguishable from a true semicircle.
     */
    public static final int CAP_SEGMENTS = 24;
    /** Upper bound for scale-adaptive round caps. */
    public static final int MAX_CAP_SEGMENTS = 256;
    /** Maximum allowed cap sagitta in final device pixels. */
    private static final float MAX_CAP_SAGITTA_PX = 0.20f;

    /** Centerline, flat {x0,y0,x1,y1,...}. */
    public final float[] centerline;
    /** Left outline (start -> end), flat xy pairs, same point count as centerline. */
    public final float[] left;
    /** Right outline (start -> end), flat xy pairs. */
    public final float[] right;
    /** Full width (diameter) per centerline point, page units. */
    public final float[] widths;
    /** Opacity 0..1 per centerline point. */
    public final float[] opacities;
    /** Stroke-level color (ARGB). */
    public final int color;
    /** Bounds {left, top, right, bottom} including half widths. */
    public final float[] bounds;

    public StrokeMesh(float[] centerline, float[] left, float[] right,
                      float[] widths, float[] opacities, int color, float[] bounds) {
        this.centerline = centerline;
        this.left = left;
        this.right = right;
        this.widths = widths;
        this.opacities = opacities;
        this.color = color;
        this.bounds = bounds;
    }

    public int pointCount() {
        return widths.length;
    }

    /** Average opacity across the stroke (renderer fills with a single alpha). */
    public float averageOpacity() {
        if (opacities.length == 0) {
            return 1f;
        }
        float sum = 0f;
        for (float o : opacities) {
            sum += o;
        }
        return sum / opacities.length;
    }

    /**
     * Closed outline polygon (flat xy pairs): left outline forward, a round
     * cap arc at the end, right outline backward, a round cap at the start.
     */
    public float[] closedOutline() {
        return closedOutline(1f);
    }

    /** Closed outline with round caps tessellated for the final pixel scale. */
    public float[] closedOutline(float pixelsPerPageUnit) {
        int n = pointCount();
        if (n == 0) {
            return new float[0];
        }
        int capSegments = capSegmentsForWidth(
                Math.max(widths[0], widths[n - 1]), pixelsPerPageUnit);
        int capPts = capSegments - 1; // interior arc points per cap
        float[] out = new float[(2 * n + 2 * capPts) * 2];
        int o = 0;
        for (int i = 0; i < n; i++) {
            out[o++] = left[i * 2];
            out[o++] = left[i * 2 + 1];
        }
        o = appendCap(out, o, n - 1, true, capSegments);
        for (int i = n - 1; i >= 0; i--) {
            out[o++] = right[i * 2];
            out[o++] = right[i * 2 + 1];
        }
        o = appendCap(out, o, 0, false, capSegments);
        return out;
    }

    /**
     * Appends the semicircle arc from the left point of center[i] to its
     * right point (or reverse), bulging outward from the stroke body.
     */
    private int appendCap(float[] out, int o, int i, boolean forward,
                          int capSegments) {
        float cx = centerline[i * 2];
        float cy = centerline[i * 2 + 1];
        float radius = widths[i] / 2f;
        // Base angle: normal direction at this centerline point.
        float nx = left[i * 2] - cx;
        float ny = left[i * 2 + 1] - cy;
        float base = (float) Math.atan2(ny, nx);
        for (int s = 1; s < capSegments; s++) {
            float t = (float) s / capSegments;
            // End cap: arc left->right bulging along the stroke direction
            // (normal rotated -90 deg). Start cap: arc right->left bulging
            // against it, so it sweeps from angle base+PI back to base.
            float angle = forward
                    ? base - t * (float) Math.PI
                    : base + (float) Math.PI - t * (float) Math.PI;
            out[o++] = cx + radius * (float) Math.cos(angle);
            out[o++] = cy + radius * (float) Math.sin(angle);
        }
        return o;
    }

    /** Number of semicircle segments needed to keep cap facets sub-pixel. */
    public static int capSegmentsForWidth(float width, float pixelsPerPageUnit) {
        float scale = Float.isFinite(pixelsPerPageUnit) && pixelsPerPageUnit > 0f
                ? pixelsPerPageUnit : 1f;
        float radiusPx = Math.max(0f, width * 0.5f * scale);
        if (radiusPx <= MAX_CAP_SAGITTA_PX) {
            return CAP_SEGMENTS;
        }
        float cosStep = 1f - MAX_CAP_SAGITTA_PX / radiusPx;
        cosStep = Math.max(-1f, Math.min(1f, cosStep));
        float step = (float) Math.acos(cosStep);
        int segments = step > 1e-6f
                ? (int) Math.ceil(Math.PI / step) : MAX_CAP_SEGMENTS;
        return Math.max(CAP_SEGMENTS, Math.min(MAX_CAP_SEGMENTS, segments));
    }
}
