package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

/** Library tag metadata. */
public final class TagItem {

    @SerializedName("id")
    public String id = IdGenerator.newId();
    @SerializedName("name")
    public String name = "";
    /** Parent tag id for nested tags; null = top level. */
    @SerializedName("parentId")
    public String parentId;
    /** Optional ARGB display color; null = theme default. */
    @SerializedName("color")
    public Integer color;
    @SerializedName("sortOrder")
    public int sortOrder;
    @SerializedName("createdAt")
    public long createdAt;

    public TagItem() {
    }
}
