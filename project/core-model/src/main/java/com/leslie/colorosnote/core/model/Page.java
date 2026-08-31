package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * A single page: background layer + vector strokes + object layer + metadata.
 * Width/height are logical coordinates (default A4 portrait ratio).
 */
public final class Page {

    /** A4 portrait at 72 dpi, used as the default logical page size. */
    public static final float DEFAULT_WIDTH = 595f;
    public static final float DEFAULT_HEIGHT = 842f;

    @SerializedName("id")
    public String id = IdGenerator.newId();
    /** 0-based position inside the document. */
    @SerializedName("index")
    public int index;
    @SerializedName("width")
    public float width = DEFAULT_WIDTH;
    @SerializedName("height")
    public float height = DEFAULT_HEIGHT;
    @SerializedName("background")
    public PageBackground background = PageBackground.blank();
    @SerializedName("strokes")
    public List<Stroke> strokes = new ArrayList<>();
    @SerializedName("objects")
    public List<PageObject> objects = new ArrayList<>();
    @SerializedName("title")
    public String title = "";
    @SerializedName("bookmarked")
    public boolean bookmarked;
    @SerializedName("createdAt")
    public long createdAt;
    @SerializedName("updatedAt")
    public long updatedAt;

    public Page() {
    }
}
