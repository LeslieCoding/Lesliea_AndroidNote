package com.leslie.colorosnote.feature.library;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.CacheTrimRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * In-memory LRU cache for document thumbnails. The .inknote container stores
 * page thumbnails as webp entries under {@code thumbnails/}; loading takes the
 * first one found. Runs on AppExecutors.background() so listing IO never
 * competes with the ink pipeline; results are delivered on the main thread.
 */
final class ThumbnailCache {

    private static final String TAG = "ThumbnailCache";

    interface Callback {
        void onThumbnailLoaded(String documentId);
    }

    private final LruCache<String, Bitmap> cache;
    private final Set<String> loading = new HashSet<>();
    private final int maxKb;
    private final CacheTrimRegistry.TrimTarget trimTarget;

    ThumbnailCache() {
        maxKb = Math.max(4096, (int) (Runtime.getRuntime().maxMemory() / 1024 / 8));
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        trimTarget = fraction -> cache.trimToSize(Math.max(1, (int) (maxKb * fraction)));
        CacheTrimRegistry.register(trimTarget);
    }

    /** Unregisters from the global memory-pressure trim registry (call on view destroy). */
    void close() {
        CacheTrimRegistry.unregister(trimTarget);
    }

    /** Returns the cached bitmap or null (and then call {@link #load}). */
    Bitmap get(String documentId) {
        return cache.get(documentId);
    }

    /** Async load; callback fires on the main thread, only when a bitmap was found. */
    synchronized void load(String documentId, File container, Callback callback) {
        if (cache.get(documentId) != null || loading.contains(documentId)) {
            return;
        }
        loading.add(documentId);
        AppExecutors.get().background().execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = readFirstThumbnail(container);
            } catch (IOException | RuntimeException e) {
                AppLogger.w(TAG, "thumbnail read failed for " + documentId);
            }
            Bitmap result = bitmap;
            AppExecutors.get().mainThread().post(() -> {
                synchronized (ThumbnailCache.this) {
                    loading.remove(documentId);
                    if (result != null) {
                        cache.put(documentId, result);
                    }
                }
                if (result != null) {
                    callback.onThumbnailLoaded(documentId);
                }
            });
        });
    }

    synchronized void invalidate(String documentId) {
        cache.remove(documentId);
    }

    /** Scans the container zip for the first entry under thumbnails/ and decodes it. */
    private static Bitmap readFirstThumbnail(File container) throws IOException {
        if (container == null || !container.exists()) {
            return null;
        }
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(container))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().startsWith("thumbnails/") && !entry.isDirectory()) {
                    return BitmapFactory.decodeStream(zin);
                }
            }
        }
        return null;
    }
}
