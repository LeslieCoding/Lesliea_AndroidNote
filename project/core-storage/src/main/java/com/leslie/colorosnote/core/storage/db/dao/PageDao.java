package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.leslie.colorosnote.core.storage.db.entity.PageEntity;

import java.util.List;

@Dao
public interface PageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<PageEntity> pages);

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex")
    List<PageEntity> listForDocument(String documentId);

    @Query("SELECT COUNT(*) FROM pages WHERE documentId = :documentId")
    int countForDocument(String documentId);

    /** Removes page rows that no longer exist in the container. */
    @Query("DELETE FROM pages WHERE documentId = :documentId AND id NOT IN (:pageIds)")
    void deleteNotIn(String documentId, List<String> pageIds);

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    void deleteForDocument(String documentId);
}
