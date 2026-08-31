package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory tape (requirement 19): a masking strip that hides underlying content
 * until tapped. Either a hand-drawn {@link #path} or a straight {@link #rect}
 * strip is set. Interactivity is preserved in the project file; PDF export
 * flattens the tape according to {@link #isRevealed}.
 */
public final class TapeObject extends PageObject {

    /**
     * Surface pattern. Only enum values are ever added here (never new
     * PageObject subtypes) so older app versions degrade leniently: an
     * unknown value deserializes to null and painters treat null as SOLID.
     */
    public enum TapePattern {SOLID, STRIPES, DOTS, GRID, CROSSHATCH, WAVES, HEARTS}

    /** How a revealed tape behaves. */
    public enum RevealMode {TAP_TOGGLE, HOLD_TO_REVEAL, NONE}

    /** Hand-drawn tape centerline, flat {x0,y0,...} local coords; null/empty when rect is used. */
    @SerializedName("path")
    public List<Float> path = new ArrayList<>();
    /** Straight tape rectangle {left,top,right,bottom}; null when path is used. */
    @SerializedName("rect")
    public float[] rect;
    /** Strip width in logical units. */
    @SerializedName("width")
    public float width = 40f;
    @SerializedName("color")
    public int color = 0xFFFFC107;
    @SerializedName("pattern")
    public TapePattern pattern = TapePattern.SOLID;
    /** True = content underneath currently visible (tape lifted). */
    @SerializedName("isRevealed")
    public boolean isRevealed;
    @SerializedName("revealMode")
    public RevealMode revealMode = RevealMode.TAP_TOGGLE;

    public TapeObject() {
        super(ObjectType.TAPE);
    }
}
