package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Recently-opened documents list, newest first (requirement 34). */
@Entity(
        tableName = "recent_documents",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("openedAt"))
public final class RecentDocumentEntity {

    @PrimaryKey
    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    @ColumnInfo(name = "openedAt")
    public long openedAt;

    public RecentDocumentEntity(String documentId) {
        this.documentId = documentId;
    }
}
