package com.leslie.colorosnote.core.common;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * 2D geometry primitives used by the eraser's stroke cutting and the lasso's
 * hit testing. Point lists are flat float arrays: {x0, y0, x1, y1, ...}.
 * All pure-math methods are JVM-safe so they can be unit tested off-device.
 */
public final class Geometry {

    private static final float EPSILON = 1e-6f;

    private Geometry() {
    }

    // ------------------------------------------------------------------
    // Distances
    // ------------------------------------------------------------------

    /** Distance from point (px,py) to the segment (ax,ay)-(bx,by). */
    public static float distancePointToSegment(float px, float py,
                                               float ax, float ay,
                                               float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float lengthSq = abx * abx + aby * aby;
        if (lengthSq < EPSILON) {
            // Degenerate segment: treat as a point.
            float dx = px - ax;
            float dy = py - ay;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        float t = ((px - ax) * abx + (py - ay) * aby) / lengthSq;
        t = Math.max(0f, Math.min(1f, t));
        float cx = ax + t * abx;
        float cy = ay + t * aby;
        float dx = px - cx;
        float dy = py - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------
    // Segment intersection (used by pixel eraser stroke cutting)
    // ------------------------------------------------------------------

    /**
     * Returns true if segment (a1,a2) intersects segment (b1,b2),
     * including endpoint touches and collinear overlaps.
     */
    public static boolean segmentsIntersect(float a1x, float a1y, float a2x, float a2y,
                                            float b1x, float b1y, float b2x, float b2y) {
        float d1 = cross(b1x, b1y, b2x, b2y, a1x, a1y);
        float d2 = cross(b1x, b1y, b2x, b2y, a2x, a2y);
        float d3 = cross(a1x, a1y, a2x, a2y, b1x, b1y);
        float d4 = cross(a1x, a1y, a2x, a2y, b2x, b2y);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (Math.abs(d1) <= EPSILON && onSegment(b1x, b1y, b2x, b2y, a1x, a1y))
                || (Math.abs(d2) <= EPSILON && onSegment(b1x, b1y, b2x, b2y, a2x, a2y))
                || (Math.abs(d3) <= EPSILON && onSegment(a1x, a1y, a2x, a2y, b1x, b1y))
                || (Math.abs(d4) <= EPSILON && onSegment(a1x, a1y, a2x, a2y, b2x, b2y));
    }

    /**
     * Computes the intersection point of the two lines through segments a and b,
     * or null when the segments do not actually intersect. Result is {x, y}.
     */
    public static float[] segmentIntersectionPoint(float a1x, float a1y, float a2x, float a2y,
                                                   float b1x, float b1y, float b2x, float b2y) {
        if (!segmentsIntersect(a1x, a1y, a2x, a2y, b1x, b1y, b2x, b2y)) {
            return null;
        }
        float denom = (a2x - a1x) * (b2y - b1y) - (a2y - a1y) * (b2x - b1x);
        if (Math.abs(denom) <= EPSILON) {
            // Collinear/parallel: no unique point; return the shared endpoint if any.
            if (onSegment(a1x, a1y, a2x, a2y, b1x, b1y)) {
                return new float[]{b1x, b1y};
            }
            if (onSegment(a1x, a1y, a2x, a2y, b2x, b2y)) {
                return new float[]{b2x, b2y};
            }
            return null;
        }
        float t = ((b1x - a1x) * (b2y - b1y) - (b1y - a1y) * (b2x - b1x)) / denom;
        return new float[]{a1x + t * (a2x - a1x), a1y + t * (a2y - a1y)};
    }

    private static float cross(float ox, float oy, float ax, float ay, float bx, float by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private static boolean onSegment(float ax, float ay, float bx, float by, float px, float py) {
        return Math.min(ax, bx) - EPSILON <= px && px <= Math.max(ax, bx) + EPSILON
                && Math.min(ay, by) - EPSILON <= py && py <= Math.max(ay, by) + EPSILON;
    }

    // ------------------------------------------------------------------
    // Point in polygon (used by lasso hit testing)
    // ------------------------------------------------------------------

    /**
     * Ray-casting point-in-polygon test.
     *
     * @param polygon flat vertex array {x0,y0,x1,y1,...}, implicitly closed
     * @param vertexCount number of vertices (<= polygon.length / 2)
     */
    public static boolean pointInPolygon(float[] polygon, int vertexCount, float px, float py) {
        boolean inside = false;
        int n = Math.min(vertexCount, polygon.length / 2);
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = polygon[i * 2];
            float yi = polygon[i * 2 + 1];
            float xj = polygon[j * 2];
            float yj = polygon[j * 2 + 1];
            if (((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    // ------------------------------------------------------------------
    // RectF helpers
    // ------------------------------------------------------------------

    /** Returns a new RectF that is the union of a and b. */
    public static RectF union(RectF a, RectF b) {
        return new RectF(Math.min(a.left, b.left), Math.min(a.top, b.top),
                Math.max(a.right, b.right), Math.max(a.bottom, b.bottom));
    }

    /** True if inner is fully contained in outer (inclusive edges). */
    public static boolean contains(RectF outer, RectF inner) {
        return outer.left <= inner.left && outer.top <= inner.top
                && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }

    /** Returns a copy of r expanded by dx/dy on all sides. */
    public static RectF outset(RectF r, float dx, float dy) {
        return new RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy);
    }

    /** True when the rect has strictly positive width and height. */
    public static boolean isValid(RectF r) {
        return r != null && r.right > r.left && r.bottom > r.top;
    }

    // ------------------------------------------------------------------
    // Catmull-Rom -> cubic Bezier conversion (ink path smoothing)
    // ------------------------------------------------------------------

    /**
     * Converts a Catmull-Rom spline through the given control points into a list
     * of cubic Bezier segments. Each returned element is
     * {c1x, c1y, c2x, c2y, endX, endY}; the start of each segment is the end of
     * the previous one (or pts[1] for the first segment).
     *
     * <p>Callers should duplicate the first and last points to make the spline
     * pass through the actual stroke endpoints.
     *
     * @param pts flat array {x0,y0,...} with at least 4 points (8 floats)
     * @return one Bezier segment per interior span (pts.length/2 - 3 segments)
     */
    public static List<float[]> catmullRomToBezier(float[] pts) {
        List<float[]> segments = new ArrayList<>();
        int count = pts.length / 2;
        if (count < 4) {
            return segments;
        }
        for (int i = 0; i + 3 < count; i++) {
            float p0x = pts[i * 2];
            float p0y = pts[i * 2 + 1];
            float p1x = pts[(i + 1) * 2];
            float p1y = pts[(i + 1) * 2 + 1];
            float p2x = pts[(i + 2) * 2];
            float p2y = pts[(i + 2) * 2 + 1];
            float p3x = pts[(i + 3) * 2];
            float p3y = pts[(i + 3) * 2 + 1];
            segments.add(new float[]{
                    p1x + (p2x - p0x) / 6f,
                    p1y + (p2y - p0y) / 6f,
                    p2x - (p3x - p1x) / 6f,
                    p2y - (p3y - p1y) / 6f,
                    p2x, p2y
            });
        }
        return segments;
    }
}
