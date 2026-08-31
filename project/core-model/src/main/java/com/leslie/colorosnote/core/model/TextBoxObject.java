package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

/** Text box object (requirement 18.2). Colors are ARGB; null = transparent. */
public final class TextBoxObject extends PageObject {

    public enum Alignment {LEFT, CENTER, RIGHT}

    @SerializedName("text")
    public String text = "";
    @SerializedName("fontSize")
    public float fontSize = 16f;
    @SerializedName("color")
    public int color = 0xFF000000;
    @SerializedName("bold")
    public boolean bold;
    @SerializedName("italic")
    public boolean italic;
    @SerializedName("underline")
    public boolean underline;
    @SerializedName("alignment")
    public Alignment alignment = Alignment.LEFT;
    /** Line spacing multiplier, 1.0 = single. */
    @SerializedName("lineSpacing")
    public float lineSpacing = 1.2f;
    /** Nullable ARGB background; null = transparent. */
    @SerializedName("backgroundColor")
    public Integer backgroundColor;
    /** Nullable ARGB border; null = no border. */
    @SerializedName("borderColor")
    public Integer borderColor;
    /** Height follows content when true. */
    @SerializedName("autoHeight")
    public boolean autoHeight = true;

    public TextBoxObject() {
        super(ObjectType.TEXT_BOX);
    }
}
