package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.FavoriteEntity;
import com.leslie.colorosnote.core.storage.db.entity.RecentDocumentEntity;

import java.util.List;

/** Recent-documents and favorites lists (requirement 34). */
@Dao
public interface LibraryExtrasDao {

    // -- recents -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void touchRecent(RecentDocumentEntity entry);

    @Query("SELECT d.* FROM documents d "
            + "INNER JOIN recent_documents r ON r.documentId = d.id "
            + "WHERE d.id NOT IN (SELECT documentId FROM trash) "
            + "ORDER BY r.openedAt DESC LIMIT :limit")
    List<DocumentEntity> listRecent(int limit);

    @Query("DELETE FROM recent_documents WHERE documentId = :documentId")
    void removeRecent(String documentId);

    // -- favorites ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addFavorite(FavoriteEntity entry);

    @Query("DELETE FROM favorites WHERE documentId = :documentId")
    void removeFavorite(String documentId);

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE documentId = :documentId)")
    boolean isFavorite(String documentId);

    @Query("SELECT d.* FROM documents d "
            + "INNER JOIN favorites f ON f.documentId = d.id "
            + "WHERE d.id NOT IN (SELECT documentId FROM trash) "
            + "ORDER BY f.addedAt DESC")
    List<DocumentEntity> listFavorites();
}
