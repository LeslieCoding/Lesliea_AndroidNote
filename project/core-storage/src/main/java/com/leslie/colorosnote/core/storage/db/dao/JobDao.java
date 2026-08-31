package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.leslie.colorosnote.core.storage.db.entity.ExportJobEntity;
import com.leslie.colorosnote.core.storage.db.entity.ImportJobEntity;

import java.util.List;

/** Import/export job tracking for WorkManager-driven retries (requirement 35-37). */
@Dao
public interface JobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertImportJob(ImportJobEntity job);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertExportJob(ExportJobEntity job);

    @Query("SELECT * FROM import_jobs WHERE id = :jobId")
    ImportJobEntity getImportJob(String jobId);

    @Query("SELECT * FROM export_jobs WHERE id = :jobId")
    ExportJobEntity getExportJob(String jobId);

    /** Unfinished import jobs, for resume/retry after process death. */
    @Query("SELECT * FROM import_jobs WHERE status IN ('PENDING', 'RUNNING') ORDER BY createdAt")
    List<ImportJobEntity> listPendingImportJobs();

    @Query("SELECT * FROM export_jobs WHERE status IN ('PENDING', 'RUNNING') ORDER BY createdAt")
    List<ExportJobEntity> listPendingExportJobs();

    @Query("UPDATE import_jobs SET status = :status, progress = :progress, "
            + "errorMessage = :error, updatedAt = :updatedAt WHERE id = :jobId")
    void updateImportJobStatus(String jobId, String status, int progress, String error, long updatedAt);

    @Query("UPDATE export_jobs SET status = :status, errorMessage = :error, "
            + "updatedAt = :updatedAt WHERE id = :jobId")
    void updateExportJobStatus(String jobId, String status, String error, long updatedAt);
}
