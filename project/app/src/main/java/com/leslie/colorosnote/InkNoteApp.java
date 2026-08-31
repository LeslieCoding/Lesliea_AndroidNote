package com.leslie.colorosnote;

import android.app.Application;
import android.content.ComponentCallbacks2;

import com.leslie.colorosnote.core.common.CacheTrimRegistry;

/**
 * Application entry point. Keeps heavy init off the main thread where possible;
 * feature modules register their own lazy initializers.
 *
 * <p>Memory pressure: Android 14+ no longer reliably delivers
 * {@code TRIM_MEMORY_RUNNING_CRITICAL} (see docs/PERFORMANCE_RESEARCH.md), so
 * every trim level is mapped to a keep-fraction and pushed into
 * {@link CacheTrimRegistry}, which shrinks all registered caches (PDF/Office
 * background bitmaps, library thumbnails).
 */
public class InkNoteApp extends Application {

    private static InkNoteApp instance;

    public static InkNoteApp getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        com.leslie.colorosnote.work.WorkScheduler.scheduleDailyTrashCleanup(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        float keepFraction;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            keepFraction = 0f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            keepFraction = 0.1f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            keepFraction = 0.25f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            keepFraction = 0.5f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            keepFraction = 0.1f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            keepFraction = 0.25f;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            keepFraction = 0.5f;
        } else {
            return;
        }
        CacheTrimRegistry.trimAll(keepFraction);
    }
}
