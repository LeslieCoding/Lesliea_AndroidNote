package com.leslie.colorosnote.work;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules background maintenance jobs (spec 35). */
public final class WorkScheduler {

    private static final String TRASH_CLEANUP = "trash_cleanup_daily";

    private WorkScheduler() {
    }

    public static void scheduleDailyTrashCleanup(Context context) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                TrashCleanupWorker.class, 24, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TRASH_CLEANUP, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
