package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Library-level document metadata (requirement 34). The heavy payload lives in
 * the .inknote container file; this row is for listing/sorting/filtering only.
 */
@Entity(
        tableName = "documents",
        foreignKeys = @ForeignKey(
                entity = FolderEntity.class,
                parentColumns = "id",
                childColumns = "folderId",
                onDelete = ForeignKey.SET_NULL),
        indices = {
                @Index("folderId"),
                @Index("updatedAt"),
                @Index(value = {"folderId", "updatedAt"})
        })
public final class DocumentEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "title")
    public String title = "";

    /** {@code NoteDocument.DocType} name, stored as string for forward compat. */
    @ColumnInfo(name = "docType")
    public String docType = "NOTE";

    /** Owning folder; null = library root. SET_NULL when the folder is deleted. */
    @ColumnInfo(name = "folderId")
    public String folderId;

    /** Container file name under the documents dir, e.g. "<id>.inknote". */
    @ColumnInfo(name = "fileName")
    public String fileName;

    @ColumnInfo(name = "pageCount")
    public int pageCount;

    @ColumnInfo(name = "schemaVersion")
    public int schemaVersion;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    public DocumentEntity(String id) {
        this.id = id;
    }
}
