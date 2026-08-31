package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One OCR text block on a page (requirement 14/34). */
@Entity(
        tableName = "ocr_blocks",
        foreignKeys = {
                @ForeignKey(
                        entity = DocumentEntity.class,
                        parentColumns = "id",
                        childColumns = "documentId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = PageEntity.class,
                        parentColumns = "id",
                        childColumns = "pageId",
                        onDelete = ForeignKey.CASCADE)
        },
        indices = {@Index("documentId"), @Index("pageId")})
public final class OcrBlockEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    @ColumnInfo(name = "pageId")
    public String pageId;

    /** Order of the block on the page, top-to-bottom. */
    @ColumnInfo(name = "blockIndex")
    public int blockIndex;

    @ColumnInfo(name = "text")
    public String text = "";

    /** Bounding box as "{left},{top},{right},{bottom}" in page coordinates. */
    @ColumnInfo(name = "boundingBox")
    public String boundingBox;

    /** 0..1 confidence reported by the OCR engine; null when unknown. */
    @ColumnInfo(name = "confidence")
    public Float confidence;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public OcrBlockEntity(String id, String documentId, String pageId) {
        this.id = id;
        this.documentId = documentId;
        this.pageId = pageId;
    }
}
