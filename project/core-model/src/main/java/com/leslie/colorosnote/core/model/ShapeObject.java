package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard geometric shape (requirement 17). {@link #points} holds the shape's
 * defining points as flat {x0,y0,x1,y1,...} in local coordinates (e.g. two
 * endpoints for lines/arrows, corner set for polygons).
 */
public final class ShapeObject extends PageObject {

    /**
     * Shape kind. Enum-only extension keeps old readers lenient: an unknown
     * value deserializes to null and painters fall back to LINE.
     */
    public enum ShapeType {
        LINE, ARROW, DOUBLE_ARROW, DASHED_LINE,
        RECT, ROUNDED_RECT, ELLIPSE, TRIANGLE, POLYGON, DIAMOND,
        CYLINDER, CONE, AXES, ANGLE_MARK
    }

    @SerializedName("shapeType")
    public ShapeType shapeType = ShapeType.LINE;
    @SerializedName("strokeColor")
    public int strokeColor = 0xFF000000;
    /** Nullable ARGB fill; null = unfilled. */
    @SerializedName("fillColor")
    public Integer fillColor;
    @SerializedName("strokeWidth")
    public float strokeWidth = 2f;
    @SerializedName("points")
    public List<Float> points = new ArrayList<>();

    public ShapeObject() {
        super(ObjectType.SHAPE);
    }
}
