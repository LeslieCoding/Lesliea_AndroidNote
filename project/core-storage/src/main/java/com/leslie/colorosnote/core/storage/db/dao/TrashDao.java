package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.leslie.colorosnote.core.storage.db.entity.TrashEntity;

import java.util.List;

@Dao
public interface TrashDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TrashEntity entry);

    @Query("SELECT * FROM trash WHERE documentId = :documentId")
    TrashEntity get(String documentId);

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    List<TrashEntity> listAll();

    /** Entries whose deletedAt is at or before the expiry cutoff timestamp. */
    @Query("SELECT * FROM trash WHERE deletedAt <= :expiryCutoffMs ORDER BY deletedAt")
    List<TrashEntity> listExpired(long expiryCutoffMs);

    @Query("DELETE FROM trash WHERE documentId = :documentId")
    void remove(String documentId);

    @Query("SELECT COUNT(*) FROM trash")
    int count();
}
