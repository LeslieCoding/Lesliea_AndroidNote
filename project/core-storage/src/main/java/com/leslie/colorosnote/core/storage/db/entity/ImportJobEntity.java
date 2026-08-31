package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Import job tracker (requirement 36/34). Status is one of
 * PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED.
 */
@Entity(
        tableName = "import_jobs",
        indices = @Index("status"))
public final class ImportJobEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    /** Source content:// or file:// uri being imported. */
    @ColumnInfo(name = "sourceUri")
    public String sourceUri = "";

    /** Resulting document id once committed; null while running. */
    @ColumnInfo(name = "targetDocumentId")
    public String targetDocumentId;

    @ColumnInfo(name = "status")
    public String status = STATUS_PENDING;

    /** 0..100 */
    @ColumnInfo(name = "progress")
    public int progress;

    @ColumnInfo(name = "errorMessage")
    public String errorMessage;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    public ImportJobEntity(String id) {
        this.id = id;
    }
}
