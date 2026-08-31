package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

/** Image placed on a page. The pixels live in resources/ inside the container. */
public final class ImageObject extends PageObject {

    /** Id of the image resource file inside the .inknote container. */
    @SerializedName("resourceId")
    public String resourceId;
    /** 0..1 */
    @SerializedName("opacity")
    public float opacity = 1f;

    public ImageObject() {
        super(ObjectType.IMAGE);
    }
}
