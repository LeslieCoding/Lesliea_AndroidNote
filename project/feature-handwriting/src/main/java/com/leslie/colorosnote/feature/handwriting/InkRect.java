package com.leslie.colorosnote.feature.handwriting;

/**
 * Lightweight immutable bounding box in page coordinates.
 * Pure JVM type so grouping and indexing stay unit-testable off-device.
 */
public final class InkRect {

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public InkRect(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) / 2f;
    }

    public float centerY() {
        return (top + bottom) / 2f;
    }

    /** Returns the smallest rect containing both. */
    public InkRect union(InkRect other) {
        return new InkRect(Math.min(left, other.left), Math.min(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
    }

    /** [left, top, right, bottom] copy for serialization. */
    public float[] toArray() {
        return new float[]{left, top, right, bottom};
    }
}
