package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Library tag; self-referencing parent for nested tags. */
@Entity(
        tableName = "tags",
        foreignKeys = @ForeignKey(
                entity = TagEntity.class,
                parentColumns = "id",
                childColumns = "parentId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("parentId"))
public final class TagEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "name")
    public String name = "";

    @ColumnInfo(name = "parentId")
    public String parentId;

    /** Optional ARGB display color; null = theme default. */
    @ColumnInfo(name = "color")
    public Integer color;

    @ColumnInfo(name = "sortOrder")
    public int sortOrder;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public TagEntity(String id) {
        this.id = id;
    }
}
