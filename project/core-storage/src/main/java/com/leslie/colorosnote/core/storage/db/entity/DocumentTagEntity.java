package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/** Many-to-many cross reference between documents and tags. */
@Entity(
        tableName = "document_tags",
        primaryKeys = {"documentId", "tagId"},
        foreignKeys = {
                @ForeignKey(
                        entity = DocumentEntity.class,
                        parentColumns = "id",
                        childColumns = "documentId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = TagEntity.class,
                        parentColumns = "id",
                        childColumns = "tagId",
                        onDelete = ForeignKey.CASCADE)
        },
        indices = {@Index("documentId"), @Index("tagId")})
public final class DocumentTagEntity {

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    @ColumnInfo(name = "tagId")
    @NonNull
    public String tagId;

    public DocumentTagEntity(String documentId, String tagId) {
        this.documentId = documentId;
        this.tagId = tagId;
    }
}
