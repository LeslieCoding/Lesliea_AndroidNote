package com.leslie.colorosnote.core.storage.trash;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;
import com.leslie.colorosnote.core.storage.db.entity.TrashEntity;
import com.leslie.colorosnote.core.storage.journal.JournalStore;

import java.io.File;
import java.util.List;

/**
 * Trash workflow (requirement 25): soft delete with 15-day retention, restore
 * to the original folder (falling back to the root-level "已恢复项目" folder),
 * immediate permanent delete (container file + journal + metadata), and a
 * sweep method invoked daily by WorkManager.
 *
 * <p>All methods perform blocking DB/file IO; call them on AppExecutors.io()
 * (the WorkManager sweep already runs on a background thread, which also
 * satisfies "cleanup must not steal I/O while the user is editing").
 */
public final class TrashManager {

    /** Folder receiving restored documents whose original folder is gone. */
    public static final String RESTORED_ITEMS_FOLDER_NAME = "已恢复项目";

    private static final String TAG = "TrashManager";

    private final InkNoteDatabase db;
    private final File documentsDir;

    public TrashManager(InkNoteDatabase db, File documentsDir) {
        this.db = db;
        this.documentsDir = documentsDir;
    }

    /** Soft delete: records deletedAt + original folder, detaches from library. */
    public void moveToTrash(String documentId) {
        long now = System.currentTimeMillis();
        DocumentEntity doc = db.documentDao().getById(documentId);
        if (doc == null) {
            return;
        }
        TrashEntity entry = new TrashEntity(documentId);
        entry.originalFolderId = doc.folderId;
        entry.deletedAt = now;
        db.trashDao().insert(entry);
        db.documentDao().moveToFolder(documentId, null, now);
        AppLogger.i(TAG, "trashed document " + documentId);
    }

    /**
     * Restores a trashed document: back into its original folder when it still
     * exists, otherwise into the root-level {@value #RESTORED_ITEMS_FOLDER_NAME}
     * folder (created on demand).
     *
     * @return false when the document was not in trash
     */
    public boolean restore(String documentId) {
        long now = System.currentTimeMillis();
        TrashEntity entry = db.trashDao().get(documentId);
        if (entry == null) {
            return false;
        }
        String targetFolderId = entry.originalFolderId;
        if (targetFolderId != null && db.folderDao().getById(targetFolderId) == null) {
            targetFolderId = ensureRestoredItemsFolder(now);
        }
        db.documentDao().moveToFolder(documentId, targetFolderId, now);
        db.trashDao().remove(documentId);
        AppLogger.i(TAG, "restored document " + documentId);
        return true;
    }

    /**
     * Permanent delete NOW: removes the .inknote container, its journal and the
     * metadata row (cascading to pages/tags/index rows).
     */
    public void deleteForever(String documentId) {
        File container = new File(documentsDir, documentId + ".inknote");
        if (container.exists() && !container.delete()) {
            AppLogger.w(TAG, "failed to delete container for " + documentId);
        }
        File journal = JournalStore.journalFileFor(documentsDir, documentId);
        if (journal.exists() && !journal.delete()) {
            AppLogger.w(TAG, "failed to delete journal for " + documentId);
        }
        db.indexDao().deleteSearchSegmentsForDocument(documentId);
        db.trashDao().remove(documentId);
        db.documentDao().deleteById(documentId);
        AppLogger.i(TAG, "permanently deleted document " + documentId);
    }

    /**
     * Purges every item past the 15-day retention period. Called daily by
     * WorkManager (requirement 25/35). Runs on the caller's thread.
     *
     * @return number of permanently deleted documents
     */
    public int sweepExpired() {
        return sweepExpired(System.currentTimeMillis());
    }

    /** Testable overload with an explicit clock. */
    public int sweepExpired(long nowMs) {
        List<TrashEntity> expired = db.trashDao().listExpired(TrashRetentionPolicy.expiryCutoffMs(nowMs));
        int purged = 0;
        for (TrashEntity entry : expired) {
            try {
                deleteForever(entry.documentId);
                purged++;
            } catch (RuntimeException e) {
                AppLogger.e(TAG, "sweep failed for " + entry.documentId, e);
            }
        }
        if (purged > 0) {
            AppLogger.i(TAG, "sweep purged " + purged + " expired documents");
        }
        return purged;
    }

    /** All trashed entries, newest first. */
    public List<TrashEntity> listTrash() {
        return db.trashDao().listAll();
    }

    /** Remaining days before auto-purge, for the trash UI. */
    public long remainingDays(TrashEntity entry) {
        return TrashRetentionPolicy.remainingDays(entry.deletedAt, System.currentTimeMillis());
    }

    private String ensureRestoredItemsFolder(long now) {
        FolderEntity existing = db.folderDao().findRootByName(RESTORED_ITEMS_FOLDER_NAME);
        if (existing != null) {
            return existing.id;
        }
        FolderEntity folder = new FolderEntity(IdGenerator.newId());
        folder.name = RESTORED_ITEMS_FOLDER_NAME;
        folder.parentId = null;
        folder.createdAt = now;
        db.folderDao().upsert(folder);
        return folder.id;
    }
}
