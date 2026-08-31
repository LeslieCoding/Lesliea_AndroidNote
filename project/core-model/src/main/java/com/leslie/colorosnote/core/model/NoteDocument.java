package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;
import com.leslie.colorosnote.core.common.IdGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Root document model (requirement 8). Serialized into document.json inside
 * the .inknote container; heavy resources (images/audio/PDF) stay as files.
 */
public final class NoteDocument {

    public enum DocType {NOTE, INFINITE_CANVAS, PDF_IMPORT, OFFICE_IMPORT, IMAGE_IMPORT}

    /** Current on-disk schema version; bump when the format changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @SerializedName("id")
    public String id = IdGenerator.newId();
    @SerializedName("title")
    public String title = "";
    @SerializedName("docType")
    public DocType docType = DocType.NOTE;
    @SerializedName("pages")
    public List<Page> pages = new ArrayList<>();
    /** Owning folder id in the library; null = root. */
    @SerializedName("folderId")
    public String folderId;
    /** Tag ids attached to this document. */
    @SerializedName("tags")
    public List<String> tags = new ArrayList<>();
    @SerializedName("createdAt")
    public long createdAt;
    @SerializedName("updatedAt")
    public long updatedAt;
    @SerializedName("schemaVersion")
    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    public NoteDocument() {
    }
}
