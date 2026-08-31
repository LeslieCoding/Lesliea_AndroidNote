package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A binary resource (image, PDF source, texture, ...) stored inside the
 * .inknote container; this row only indexes it (requirement 9/34).
 */
@Entity(
        tableName = "resources",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("documentId"))
public final class ResourceEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    /** Logical type, e.g. "image", "pdf", "texture". */
    @ColumnInfo(name = "type")
    public String type = "";

    /** Path inside the container, e.g. "resources/<id>". */
    @ColumnInfo(name = "containerPath")
    public String containerPath = "";

    @ColumnInfo(name = "mimeType")
    public String mimeType;

    @ColumnInfo(name = "sizeBytes")
    public long sizeBytes;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public ResourceEntity(String id, String documentId) {
        this.id = id;
        this.documentId = documentId;
    }
}
