package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Per-page metadata mirror; the full page content lives in the container. */
@Entity(
        tableName = "pages",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "documentId",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index("documentId"),
                @Index(value = {"documentId", "pageIndex"}, unique = true)
        })
public final class PageEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    /** 0-based position; named pageIndex because "index" is a SQL keyword. */
    @ColumnInfo(name = "pageIndex")
    public int pageIndex;

    @ColumnInfo(name = "title")
    public String title = "";

    @ColumnInfo(name = "bookmarked")
    public boolean bookmarked;

    @ColumnInfo(name = "width")
    public float width;

    @ColumnInfo(name = "height")
    public float height;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    public PageEntity(String id, String documentId) {
        this.id = id;
        this.documentId = documentId;
    }
}
