package com.leslie.colorosnote.core.model;

import android.graphics.Matrix;
import android.graphics.RectF;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * A vector ink stroke. Points are kept in logical page coordinates so strokes
 * never pixelate on zoom. The full brush preset snapshot is embedded so the
 * stroke can be re-rendered exactly even if the preset is later edited/deleted.
 */
public final class Stroke {

    /** Layer a stroke lives on; the eraser can filter by layer. */
    public enum Layer {INK, HIGHLIGHT}

    @SerializedName("id")
    public String id = IdGenerator.newId();
    /** Id of the preset in the user's brush library. */
    @SerializedName("brushPresetId")
    public String brushPresetId;
    /** Full snapshot of the brush preset used to draw this stroke. */
    @SerializedName("brushPreset")
    public BrushPreset brushPreset;
    /** ARGB color actually used (copied from the preset at draw time). */
    @SerializedName("color")
    public int color;
    @SerializedName("points")
    public List<StrokePoint> points = new ArrayList<>();
    @SerializedName("zIndex")
    public int zIndex;
    @SerializedName("createdAtMs")
    public long createdAtMs;
    /** Audio-timeline sync anchors (requirement 20.1); null when not recording. */
    @SerializedName("strokeStartAudioTimeMs")
    public Long strokeStartAudioTimeMs;
    @SerializedName("strokeEndAudioTimeMs")
    public Long strokeEndAudioTimeMs;
    @SerializedName("layer")
    public Layer layer = Layer.INK;

    public Stroke() {
    }

    // ------------------------------------------------------------------
    // Bounds
    // ------------------------------------------------------------------

    /** Computes the axis-aligned bounding box of all sample points. */
    public RectF bounds() {
        float[] b = computeBounds();
        return new RectF(b[0], b[1], b[2], b[3]);
    }

    /**
     * Pure-JVM bounds computation, returned as {left, top, right, bottom}.
     * Returns zeros for an empty stroke. Kept separate from {@link #bounds()}
     * so the math is unit-testable off-device.
     */
    public float[] computeBounds() {
        if (points.isEmpty()) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (StrokePoint p : points) {
            left = Math.min(left, p.x);
            top = Math.min(top, p.y);
            right = Math.max(right, p.x);
            bottom = Math.max(bottom, p.y);
        }
        return new float[]{left, top, right, bottom};
    }

    // ------------------------------------------------------------------
    // Transform
    // ------------------------------------------------------------------

    /** Applies an Android matrix to every point (lasso move/scale/rotate). */
    public void transform(Matrix matrix) {
        float[] values = new float[9];
        matrix.getValues(values);
        transformValues(values);
    }

    /**
     * Pure-JVM transform using the 9 matrix values in Android Matrix order
     * (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, MPERSP_2).
     * Perspective terms are ignored (page edits are affine).
     */
    public void transformValues(float[] m) {
        float scaleX = m[0];
        float skewX = m[1];
        float transX = m[2];
        float skewY = m[3];
        float scaleY = m[4];
        float transY = m[5];
        for (StrokePoint p : points) {
            float x = p.x;
            float y = p.y;
            p.x = scaleX * x + skewX * y + transX;
            p.y = skewY * x + scaleY * y + transY;
        }
    }
}
