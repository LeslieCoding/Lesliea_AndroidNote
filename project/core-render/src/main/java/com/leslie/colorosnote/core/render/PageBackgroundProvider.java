package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;

/**
 * Supplies document page bitmaps (PDF / Office) used as note-page backgrounds
 * ({@link com.leslie.colorosnote.core.model.PageBackground.Type#PDF} /
 * OFFICE). The template/solid-color backgrounds are drawn by
 * {@link PageRenderer} itself and do not go through this interface.
 *
 * <p>Implementations keep rendered pages in an LRU memory cache keyed by
 * (documentId, pageIndex, width tier) — see {@link BackgroundCacheKey}.
 * OWNERSHIP: returned bitmaps stay owned by the provider and may be recycled
 * on eviction or {@link #release()}; callers must never recycle them and must
 * not hold them across {@link #release()}.
 *
 * <p>Threading contract: {@link #getPageBitmap} may block on rendering, so
 * callers should pre-warm off the main thread; implementations must be
 * internally thread-safe.
 */
public interface PageBackgroundProvider {

    /** Stable id of the backing document; part of the cache key. */
    String documentId();

    /** Number of pages in the backing document, or 0 when unavailable. */
    int pageCount();

    /**
     * Returns the background bitmap for {@code pageIndex} (0-based), rendered
     * at the quantized width tier of {@code targetWidthPx} (height follows the
     * page aspect ratio), or null when the index is out of range, the page
     * cannot be rendered, or the provider has been released.
     */
    Bitmap getPageBitmap(int pageIndex, int targetWidthPx);

    /** Releases all cached bitmaps; later calls return null. Idempotent. */
    void release();

    /** Default LRU budget: 1/8 of the VM max heap, at least 8 MB. */
    static int defaultCacheBytes() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long budget = Math.max(8L * 1024 * 1024, maxHeap / 8);
        return (int) Math.min(budget, Integer.MAX_VALUE);
    }
}
