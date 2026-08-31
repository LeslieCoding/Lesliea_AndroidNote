package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.leslie.colorosnote.core.storage.db.entity.DocumentTagEntity;
import com.leslie.colorosnote.core.storage.db.entity.TagEntity;

import java.util.List;

@Dao
public abstract class TagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsert(TagEntity tag);

    @Query("SELECT * FROM tags ORDER BY sortOrder, name")
    public abstract List<TagEntity> listAll();

    @Query("DELETE FROM tags WHERE id = :tagId")
    public abstract void deleteById(String tagId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract void attach(List<DocumentTagEntity> refs);

    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    public abstract void detachAll(String documentId);

    @Query("SELECT t.* FROM tags t INNER JOIN document_tags dt ON dt.tagId = t.id "
            + "WHERE dt.documentId = :documentId ORDER BY t.sortOrder, t.name")
    public abstract List<TagEntity> tagsForDocument(String documentId);

    /** Atomically replaces the full tag set of a document. */
    @Transaction
    public void setTagsForDocument(String documentId, List<String> tagIds) {
        detachAll(documentId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<DocumentTagEntity> refs = new java.util.ArrayList<>(tagIds.size());
        for (String tagId : tagIds) {
            refs.add(new DocumentTagEntity(documentId, tagId));
        }
        attach(refs);
    }
}
