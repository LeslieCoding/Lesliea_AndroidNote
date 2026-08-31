package com.leslie.colorosnote.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.trash.TrashManager;

import java.io.File;

/**
 * Daily trash cleanup (spec 25): permanently deletes items past the 15-day
 * retention. Runs at low priority; skips when the editor is active to avoid
 * stealing I/O while the user writes (spec 25: 自动清理不能抢 I/O).
 */
public final class TrashCleanupWorker extends Worker {

    private static final String TAG = "TrashCleanup";

    /** Set by the editor while a document is being edited. */
    private static volatile boolean editorActive = false;

    public static void setEditorActive(boolean active) {
        editorActive = active;
    }

    public TrashCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (editorActive) {
            AppLogger.i(TAG, "editor active, deferring trash sweep");
            return Result.retry();
        }
        try {
            File docsDir = new File(getApplicationContext().getFilesDir(), "documents");
            InkNoteDatabase db = InkNoteDatabase.build(getApplicationContext());
            TrashManager trash = new TrashManager(db, docsDir);
            int removed = trash.sweepExpired();
            AppLogger.i(TAG, "trash sweep done, removed=" + removed);
            return Result.success();
        } catch (Exception e) {
            AppLogger.e(TAG, "trash sweep failed", e);
            return Result.retry();
        }
    }
}
