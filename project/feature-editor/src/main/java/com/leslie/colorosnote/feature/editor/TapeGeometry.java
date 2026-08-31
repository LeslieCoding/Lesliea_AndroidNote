package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.common.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM geometry helpers for the memory-tape tool (spec 19): path
 * simplification for hand-drawn tapes, bounds/rect computation and hit-test
 * distances. No android.graphics types so the logic is unit-testable.
 */
public final class TapeGeometry {

    private TapeGeometry() {
    }

    /**
     * Ramer–Douglas–Peucker simplification of a flat polyline {x0,y0,...}.
     * Endpoints are always kept; returns a new list.
     */
    public static List<Float> simplify(List<Float> path, float tolerance) {
        int points = path.size() / 2;
        if (points <= 2) {
            return new ArrayList<>(path);
        }
        boolean[] keep = new boolean[points];
        keep[0] = true;
        keep[points - 1] = true;
        simplifyRange(path, 0, points - 1, tolerance, keep);
        List<Float> out = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            if (keep[i]) {
                out.add(path.get(i * 2));
                out.add(path.get(i * 2 + 1));
            }
        }
        return out;
    }

    private static void simplifyRange(List<Float> path, int first, int last,
                                      float tolerance, boolean[] keep) {
        if (last <= first + 1) {
            return;
        }
        float ax = path.get(first * 2);
        float ay = path.get(first * 2 + 1);
        float bx = path.get(last * 2);
        float by = path.get(last * 2 + 1);
        float maxDist = -1f;
        int maxIndex = -1;
        for (int i = first + 1; i < last; i++) {
            float d = Geometry.distancePointToSegment(
                    path.get(i * 2), path.get(i * 2 + 1), ax, ay, bx, by);
            if (d > maxDist) {
                maxDist = d;
                maxIndex = i;
            }
        }
        if (maxDist > tolerance && maxIndex > 0) {
            keep[maxIndex] = true;
            simplifyRange(path, first, maxIndex, tolerance, keep);
            simplifyRange(path, maxIndex, last, tolerance, keep);
        }
    }

    /** Bounds {l,t,r,b} of a hand-drawn tape including half the strip width. */
    public static float[] pathBounds(List<Float> path, float width) {
        if (path.isEmpty()) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < path.size(); i += 2) {
            l = Math.min(l, path.get(i));
            t = Math.min(t, path.get(i + 1));
            r = Math.max(r, path.get(i));
            b = Math.max(b, path.get(i + 1));
        }
        float half = width / 2f;
        return new float[]{l - half, t - half, r + half, b + half};
    }

    /**
     * Straight-tape rectangle from a drag start→end: the segment's bounding
     * box with the thinner dimension expanded to the strip width.
     */
    public static float[] straightRect(float x0, float y0, float x1, float y1, float width) {
        float l = Math.min(x0, x1);
        float r = Math.max(x0, x1);
        float t = Math.min(y0, y1);
        float b = Math.max(y0, y1);
        if (r - l < width) {
            float cx = (l + r) / 2f;
            l = cx - width / 2f;
            r = cx + width / 2f;
        }
        if (b - t < width) {
            float cy = (t + b) / 2f;
            t = cy - width / 2f;
            b = cy + width / 2f;
        }
        return new float[]{l, t, r, b};
    }

    /** Minimum distance from a point to a flat polyline (centerline). */
    public static float distanceToPath(List<Float> path, float x, float y) {
        if (path.size() < 2) {
            return Float.MAX_VALUE;
        }
        if (path.size() == 2) {
            return (float) Math.hypot(x - path.get(0), y - path.get(1));
        }
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 3 < path.size(); i += 2) {
            float d = Geometry.distancePointToSegment(x, y,
                    path.get(i), path.get(i + 1), path.get(i + 2), path.get(i + 3));
            if (d < best) {
                best = d;
            }
        }
        return best;
    }
}
