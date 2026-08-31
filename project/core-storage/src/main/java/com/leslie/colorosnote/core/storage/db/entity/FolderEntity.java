package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Library folder; self-referencing parent for nesting (requirement 23/34). */
@Entity(
        tableName = "folders",
        foreignKeys = @ForeignKey(
                entity = FolderEntity.class,
                parentColumns = "id",
                childColumns = "parentId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("parentId"))
public final class FolderEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "name")
    public String name = "";

    /** Parent folder id; null = library root. */
    @ColumnInfo(name = "parentId")
    public String parentId;

    @ColumnInfo(name = "sortOrder")
    public int sortOrder;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public FolderEntity(String id) {
        this.id = id;
    }
}
