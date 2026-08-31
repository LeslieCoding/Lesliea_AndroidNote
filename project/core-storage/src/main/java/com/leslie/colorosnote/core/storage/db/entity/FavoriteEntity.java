package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/** Favorited (starred) documents (requirement 34). */
@Entity(
        tableName = "favorites",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.CASCADE))
public final class FavoriteEntity {

    @PrimaryKey
    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    @ColumnInfo(name = "addedAt")
    public long addedAt;

    public FavoriteEntity(String documentId) {
        this.documentId = documentId;
    }
}
