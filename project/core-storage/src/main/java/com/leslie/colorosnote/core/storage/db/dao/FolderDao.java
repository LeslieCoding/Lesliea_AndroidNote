package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;

import java.util.List;

@Dao
public interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(FolderEntity folder);

    @Query("SELECT * FROM folders WHERE id = :folderId")
    FolderEntity getById(String folderId);

    /** Root-level folder with the given name, used for "已恢复项目". */
    @Query("SELECT * FROM folders WHERE parentId IS NULL AND name = :name LIMIT 1")
    FolderEntity findRootByName(String name);

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, createdAt")
    List<FolderEntity> listChildren(String parentId);

    @Query("DELETE FROM folders WHERE id = :folderId")
    void deleteById(String folderId);
}
