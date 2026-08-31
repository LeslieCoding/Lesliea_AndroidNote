package com.leslie.colorosnote.document.office;

/** One embedded image extracted from the source document; referenced by id from pages. */
public final class ImageResource {

    /** Stable id, also a safe relative file name, e.g. "img-003.png". */
    private final String id;
    private final String mimeType;
    private final byte[] data;

    public ImageResource(String id, String mimeType, byte[] data) {
        this.id = id;
        this.mimeType = mimeType;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public String getMimeType() {
        return mimeType;
    }

    /** Raw encoded image bytes (png/jpeg/gif/...), never a decoded bitmap. */
    public byte[] getData() {
        return data;
    }
}
