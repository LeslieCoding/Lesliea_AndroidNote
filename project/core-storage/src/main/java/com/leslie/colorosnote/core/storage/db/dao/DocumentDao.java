package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;

import java.util.List;

/** CRUD + library listing for documents. Trashed rows are excluded from listings. */
@Dao
public interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DocumentEntity document);

    @Update
    void update(DocumentEntity document);

    @Query("SELECT * FROM documents WHERE id = :documentId")
    DocumentEntity getById(String documentId);

    /**
     * Lists non-trashed documents of a folder; null folderId = library root.
     * {@code IS} matches both NULL and plain equality in SQLite.
     */
    @Query("SELECT * FROM documents WHERE folderId IS :folderId "
            + "AND id NOT IN (SELECT documentId FROM trash) "
            + "ORDER BY updatedAt DESC")
    List<DocumentEntity> listByFolder(String folderId);

    @Query("SELECT * FROM documents WHERE id NOT IN (SELECT documentId FROM trash) "
            + "ORDER BY updatedAt DESC")
    List<DocumentEntity> listAllActive();

    @Query("UPDATE documents SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :documentId")
    void moveToFolder(String documentId, String folderId, long updatedAt);

    @Query("UPDATE documents SET updatedAt = :updatedAt WHERE id = :documentId")
    void touch(String documentId, long updatedAt);

    @Query("UPDATE documents SET pageCount = :pageCount, updatedAt = :updatedAt WHERE id = :documentId")
    void updatePageCount(String documentId, int pageCount, long updatedAt);

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :documentId")
    void rename(String documentId, String title, long updatedAt);

    /** Hard delete; dependent rows cascade via foreign keys. */
    @Query("DELETE FROM documents WHERE id = :documentId")
    void deleteById(String documentId);

    @Query("SELECT COUNT(*) FROM documents")
    int count();
}
