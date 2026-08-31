package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/**
 * Soft-delete record (requirement 25/34). The original folder is remembered so
 * restore can put the document back; retention is enforced by TrashManager.
 */
@Entity(
        tableName = "trash",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.CASCADE))
public final class TrashEntity {

    @PrimaryKey
    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    /** Folder the document lived in when trashed; null = library root. */
    @ColumnInfo(name = "originalFolderId")
    public String originalFolderId;

    @ColumnInfo(name = "deletedAt")
    public long deletedAt;

    public TrashEntity(String documentId) {
        this.documentId = documentId;
    }
}
