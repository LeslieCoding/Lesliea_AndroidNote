package com.leslie.colorosnote.core.ink;

/**
 * Numerically safe primitives shared by live and committed stroke rendering.
 *
 * <p>A traditional variable-width outline offsets every centerline knot by
 * its averaged tangent normal, then closes one large polygon.  When a stroke
 * turns more tightly than its radius (common while writing at extreme zoom),
 * those offset rails cross.  The resulting bow-tie polygon has undefined-looking
 * winding regions: Android's path filler can produce large wedges and triangular
 * holes even though every input sample is present.
 *
 * <p>This helper instead builds each centerline interval in its own local frame.
 * The returned quad can never cross because both endpoint normals come from the
 * interval itself.  Renderers union these consistently-wound quads with round
 * discs at the centerline knots, producing a gap-free round-join stroke without
 * relying on a potentially self-intersecting global outline.
 */
public final class StrokeGeometry {

    private static final float MIN_SEGMENT_LENGTH = 1e-6f;
    private static final float MIN_TURN_CROSS = 1e-4f;
    /** No extra join geometry is needed for a straight continuation. */
    public static final int JOIN_NONE = 0;
    /** A consistently-wound outer wedge triangle closes the turn. */
    public static final int JOIN_TRIANGLE = 1;
    /** A very sharp/reversing turn needs a round disc instead of a bevel. */
    public static final int JOIN_ROUND = 2;

    private StrokeGeometry() {
    }

    /**
     * Writes a safe quad {@code {l0,l1,r1,r0}} for one variable-width segment.
     * Widths are full diameters.  Returns false for invalid or coincident input;
     * callers should still draw the endpoint discs in that case.
     */
    public static boolean segmentQuad(float x0, float y0, float width0,
                                      float x1, float y1, float width1,
                                      float[] out8) {
        if (out8 == null || out8.length < 8
                || !finite(x0) || !finite(y0) || !finite(width0)
                || !finite(x1) || !finite(y1) || !finite(width1)) {
            return false;
        }
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.hypot(dx, dy);
        if (length <= MIN_SEGMENT_LENGTH) {
            return false;
        }
        float nx = -dy / length;
        float ny = dx / length;
        float r0 = Math.max(0f, width0) * 0.5f;
        float r1 = Math.max(0f, width1) * 0.5f;

        out8[0] = x0 + nx * r0;
        out8[1] = y0 + ny * r0;
        out8[2] = x1 + nx * r1;
        out8[3] = y1 + ny * r1;
        out8[4] = x1 - nx * r1;
        out8[5] = y1 - ny * r1;
        out8[6] = x0 - nx * r0;
        out8[7] = y0 - ny * r0;
        return true;
    }

    /**
     * Builds the lightweight outer wedge between two safe segment quads.
     * {@code previous/next D*} are travel vectors meeting at {@code (cx,cy)}.
     * The six output floats are a triangle with the same negative signed area
     * as {@link #segmentQuad}; this is important for non-zero Path winding.
     */
    public static int joinTriangle(float previousDx, float previousDy,
                                   float nextDx, float nextDy,
                                   float cx, float cy, float width,
                                   float[] out6) {
        if (out6 == null || out6.length < 6 || !finite(cx) || !finite(cy)
                || !finite(width)) {
            return JOIN_NONE;
        }
        float prevLen = (float) Math.hypot(previousDx, previousDy);
        float nextLen = (float) Math.hypot(nextDx, nextDy);
        if (prevLen <= MIN_SEGMENT_LENGTH || nextLen <= MIN_SEGMENT_LENGTH) {
            return JOIN_ROUND;
        }
        float px = previousDx / prevLen;
        float py = previousDy / prevLen;
        float nx = nextDx / nextLen;
        float ny = nextDy / nextLen;
        float dot = Math.max(-1f, Math.min(1f, px * nx + py * ny));
        float cross = px * ny - py * nx;
        if (Math.abs(cross) < MIN_TURN_CROSS) {
            return dot < 0f ? JOIN_ROUND : JOIN_NONE;
        }
        // Bevels above 60 degrees are visibly flat and numerically crowded;
        // they are rare after spline refinement, so retain a round join there.
        if (dot < 0.5f) {
            return JOIN_ROUND;
        }

        float radius = Math.max(0f, width) * 0.5f;
        float prevNormalX = -py;
        float prevNormalY = px;
        float nextNormalX = -ny;
        float nextNormalY = nx;
        // For a positive turn the negative-normal rail is outside; vice versa.
        float side = cross > 0f ? -1f : 1f;
        out6[0] = cx + prevNormalX * radius * side;
        out6[1] = cy + prevNormalY * radius * side;
        out6[2] = cx;
        out6[3] = cy;
        out6[4] = cx + nextNormalX * radius * side;
        out6[5] = cy + nextNormalY * radius * side;

        float twiceArea = out6[0] * out6[3] + out6[2] * out6[5]
                + out6[4] * out6[1] - out6[2] * out6[1]
                - out6[4] * out6[3] - out6[0] * out6[5];
        if (twiceArea > 0f) {
            float tx = out6[0];
            float ty = out6[1];
            out6[0] = out6[4];
            out6[1] = out6[5];
            out6[4] = tx;
            out6[5] = ty;
        }
        return JOIN_TRIANGLE;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
