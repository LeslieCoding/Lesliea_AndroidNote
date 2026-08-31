package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Recognized handwriting text for search (requirement 15/22/34). */
@Entity(
        tableName = "handwriting_index",
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
public final class HandwritingIndexEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    @ColumnInfo(name = "pageId")
    public String pageId;

    /** Stroke this text was recognized from; null for whole-page recognition. */
    @ColumnInfo(name = "strokeId")
    public String strokeId;

    @ColumnInfo(name = "recognizedText")
    public String recognizedText = "";

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public HandwritingIndexEntity(String id, String documentId, String pageId) {
        this.id = id;
        this.documentId = documentId;
        this.pageId = pageId;
    }
}
