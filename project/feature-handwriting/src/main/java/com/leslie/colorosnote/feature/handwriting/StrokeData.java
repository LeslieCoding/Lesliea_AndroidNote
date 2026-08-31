package com.leslie.colorosnote.feature.handwriting;

/**
 * Minimal stroke model for recognition: ordered points with timestamps.
 *
 * <p>Deliberately independent from core-model / core-ink stroke classes to avoid
 * a dependency cycle (spec 15); upper layers adapt their stroke model into this
 * one. Arrays are kept as passed - treat as read-only.
 */
public final class StrokeData {

    public final String id;
    public final float[] xs;
    public final float[] ys;
    /** Milliseconds per point (same epoch across strokes of a page). */
    public final long[] ts;

    public StrokeData(String id, float[] xs, float[] ys, long[] ts) {
        if (xs == null || ys == null || ts == null
                || xs.length != ys.length || xs.length != ts.length || xs.length == 0) {
            throw new IllegalArgumentException("xs/ys/ts must be non-empty and equally sized");
        }
        this.id = id;
        this.xs = xs;
        this.ys = ys;
        this.ts = ts;
    }

    public int pointCount() {
        return xs.length;
    }

    public long startTime() {
        return ts[0];
    }

    public long endTime() {
        return ts[ts.length - 1];
    }

    public InkRect bounds() {
        float l = xs[0], r = xs[0], t = ys[0], b = ys[0];
        for (int i = 1; i < xs.length; i++) {
            l = Math.min(l, xs[i]);
            r = Math.max(r, xs[i]);
            t = Math.min(t, ys[i]);
            b = Math.max(b, ys[i]);
        }
        return new InkRect(l, t, r, b);
    }
}
