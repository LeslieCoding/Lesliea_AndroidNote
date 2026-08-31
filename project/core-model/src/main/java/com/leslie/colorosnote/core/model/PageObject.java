package com.leslie.colorosnote.core.model;

import android.graphics.Matrix;
import android.graphics.RectF;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

/**
 * Base class for all object-layer items on a page (image / text box / shape /
 * sticker / tape). The transform is stored as 9 floats (Android Matrix values
 * order) so the model stays Gson-serializable without Android runtime classes.
 *
 * <p>{@link #objectType} is a self-describing discriminator; polymorphic
 * (de)serialization of {@code List<PageObject>} is handled by core-storage via
 * a Gson TypeAdapter keyed on this field.
 */
public abstract class PageObject {

    /** Discriminator for polymorphic serialization. */
    public enum ObjectType {IMAGE, TEXT_BOX, SHAPE, STICKER, TAPE}

    @SerializedName("objectType")
    public ObjectType objectType;

    @SerializedName("id")
    public String id = IdGenerator.newId();
    @SerializedName("zIndex")
    public int zIndex;
    /** 9 floats in android.graphics.Matrix values order; identity by default. */
    @SerializedName("transform")
    public float[] transform = identityMatrix();
    @SerializedName("locked")
    public boolean locked;
    /** Local (pre-transform) bounding box: {left, top, right, bottom}. */
    @SerializedName("bounds")
    public float[] bounds = new float[]{0f, 0f, 0f, 0f};

    protected PageObject(ObjectType objectType) {
        this.objectType = objectType;
    }

    /** No-arg constructor required by Gson reflection. */
    protected PageObject() {
    }

    public static float[] identityMatrix() {
        return new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
    }

    /** Copies the stored transform into an Android Matrix. */
    public Matrix toMatrix() {
        Matrix m = new Matrix();
        m.setValues(transform);
        return m;
    }

    /** Replaces the stored transform with the values of the given Matrix. */
    public void fromMatrix(Matrix matrix) {
        matrix.getValues(transform);
    }

    /** Local bounds as a RectF. */
    public RectF boundsRect() {
        return new RectF(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    public void setBounds(RectF r) {
        bounds = new float[]{r.left, r.top, r.right, r.bottom};
    }

    /** Transformed (world-space) bounds = local bounds mapped by the transform. */
    public RectF worldBounds() {
        RectF r = boundsRect();
        toMatrix().mapRect(r);
        return r;
    }
}
