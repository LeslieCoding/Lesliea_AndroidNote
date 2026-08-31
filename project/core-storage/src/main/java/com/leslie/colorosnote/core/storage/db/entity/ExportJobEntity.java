package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Export job tracker (requirement 37/34). Status values mirror ImportJobEntity.
 */
@Entity(
        tableName = "export_jobs",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.SET_NULL),
        indices = {@Index("status"), @Index("documentId")})
public final class ExportJobEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    /** Exported document; SET_NULL keeps the job log after deletion. */
    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    /** Destination content:// or file:// uri. */
    @ColumnInfo(name = "targetUri")
    public String targetUri = "";

    /** "pdf" / "inknote" / "png" / ... */
    @ColumnInfo(name = "format")
    public String format = "";

    @ColumnInfo(name = "status")
    public String status = ImportJobEntity.STATUS_PENDING;

    @ColumnInfo(name = "errorMessage")
    public String errorMessage;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    public ExportJobEntity(String id) {
        this.id = id;
    }
}
