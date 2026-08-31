package com.leslie.colorosnote.feature.ocr;

/**
 * Lightweight immutable bounding box in pixel coordinates.
 *
 * <p>Deliberately a pure JVM type (no android.graphics) so OCR results stay
 * unit-testable off-device; convert to Rect/RectF at the UI boundary if needed.
 */
public final class OcrBox {

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public OcrBox(float left, float top, float right, float bottom) {
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

    /** Returns a copy scaled by (sx, sy), used to map back to original image coordinates. */
    public OcrBox scaled(float sx, float sy) {
        return new OcrBox(left * sx, top * sy, right * sx, bottom * sy);
    }

    /** True when the box has strictly positive width and height. */
    public boolean isValid() {
        return right > left && bottom > top;
    }
}
