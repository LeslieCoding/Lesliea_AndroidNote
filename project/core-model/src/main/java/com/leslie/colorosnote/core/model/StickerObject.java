package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

/** Sticker object (requirement 18.3). Built-in stickers use stickerId only. */
public final class StickerObject extends PageObject {

    /** Id of a built-in sticker, or of the user-saved sticker entry. */
    @SerializedName("stickerId")
    public String stickerId;
    /** Resource id inside the container for user-imported (PNG/WebP) stickers. */
    @SerializedName("resourceId")
    public String resourceId;

    public StickerObject() {
        super(ObjectType.STICKER);
    }
}
