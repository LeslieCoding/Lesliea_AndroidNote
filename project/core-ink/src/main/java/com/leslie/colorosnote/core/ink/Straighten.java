package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.List;

/**
 * Highlighter "straighten" (拉直) transform (v3.2.3).
 *
 * <p>When the highlighter's straighten toggle is on, strokes that are roughly
 * straight (the typical highlight drag) are snapped onto the line between
 * their first and last sample so they come out perfectly straight. Strokes
 * that deviate beyond a tolerance (curves, circles, scribbles) are left
 * untouched — the toggle must never fight the hand.
 *
 * <p>Pure JVM so it is unit-testable; the renderer calls
 * {@link #apply(List)} on the finished stroke's points before it is baked.
 */
public final class Straighten {

    /**
     * Maximum perpendicular deviation, as a fraction of the stroke length,
     * that still counts as "roughly straight" and gets pulled onto the line.
     * A hand-drawn highlight wiggles a few percent of its length; a genuine
     * curve or scribble stays far above this and is left alone.
     */
    private static final float MAX_DEVIATION_FRACTION = 0.06f;

    private Straighten() {
    }

    /**
     * Straightens {@code points} in place if they form a roughly straight
     * stroke. Returns true when the points were modified.
     */
    public static boolean apply(List<StrokePoint> points) {
        int n = points.size();
        if (n < 3) {
            return false;
        }
        StrokePoint a = points.get(0);
        StrokePoint b = points.get(n - 1);
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) {
            return false; // degenerate dot: nothing to straighten
        }
        float ux = dx / len;
        float uy = dy / len;
        // Farthest perpendicular distance from the start→end line.
        float maxDev = 0f;
        for (int i = 0; i < n; i++) {
            StrokePoint p = points.get(i);
            float dev = Math.abs((p.x - a.x) * uy - (p.y - a.y) * ux);
            if (dev > maxDev) {
                maxDev = dev;
            }
        }
        if (maxDev > len * MAX_DEVIATION_FRACTION) {
            return false;
        }
        // Project every sample onto the segment, preserving its fraction
        // along the line so the stroke neither shrinks nor stretches.
        for (int i = 0; i < n; i++) {
            StrokePoint p = points.get(i);
            float t = ((p.x - a.x) * ux + (p.y - a.y) * uy) / len;
            t = t < 0f ? 0f : (t > 1f ? 1f : t);
            p.x = a.x + ux * t * len;
            p.y = a.y + uy * t * len;
        }
        return true;
    }
}
