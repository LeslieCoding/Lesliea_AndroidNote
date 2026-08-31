package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

/** Library folder metadata (requirement 23). */
public final class FolderItem {

    @SerializedName("id")
    public String id = IdGenerator.newId();
    @SerializedName("name")
    public String name = "";
    /** Parent folder id; null = library root. */
    @SerializedName("parentId")
    public String parentId;
    @SerializedName("sortOrder")
    public int sortOrder;
    @SerializedName("createdAt")
    public long createdAt;

    public FolderItem() {
    }
}
