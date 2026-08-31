package com.leslie.colorosnote.core.render;

import com.leslie.colorosnote.document.pdf.LruMemoryCache;

/**
 * Pure-Java core of the document background providers: page bounds checking,
 * width-tier cache keys and the byte-budgeted LRU policy. Android-free so the
 * caching behaviour is unit-testable on the JVM; the providers only supply the
 * actual page rendering and the bitmap byte weighing.
 *
 * @param <V> cached page representation (a Bitmap in production)
 */
final class BackgroundRenderCache<V> {

    /** Renders one page at the width requested by the key, or null on failure. */
    interface Renderer<V> {
        V render(BackgroundCacheKey key);
    }

    /** Live page count of the backing document. */
    interface PageCount {
        int pageCount();
    }

    private final String documentId;
    private final PageCount pageCount;
    private final Renderer<V> renderer;
    private final LruMemoryCache<BackgroundCacheKey, V> cache;
    private volatile boolean released;

    BackgroundRenderCache(String documentId, PageCount pageCount, Renderer<V> renderer,
                          int cacheBytes,
                          LruMemoryCache.Weigher<BackgroundCacheKey, V> weigher,
                          LruMemoryCache.EvictionListener<BackgroundCacheKey, V> evictionListener) {
        this.documentId = documentId;
        this.pageCount = pageCount;
        this.renderer = renderer;
        this.cache = new LruMemoryCache<>(cacheBytes, weigher, evictionListener);
    }

    String documentId() {
        return documentId;
    }

    int pageCount() {
        return pageCount.pageCount();
    }

    boolean isReleased() {
        return released;
    }

    /**
     * Returns the cached or freshly rendered page for {@code pageIndex}
     * (0-based) at the quantized tier of {@code targetWidthPx}; null when
     * released, out of range, or when rendering failed (failures are not
     * cached, so a later call retries).
     */
    V get(int pageIndex, int targetWidthPx) {
        if (released) {
            return null;
        }
        int count = pageCount.pageCount();
        if (pageIndex < 0 || pageIndex >= count) {
            return null;
        }
        BackgroundCacheKey key = keyFor(pageIndex, targetWidthPx);
        V cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        V rendered = renderer.render(key);
        if (rendered == null) {
            return null;
        }
        cache.put(key, rendered);
        return rendered;
    }

    /** Drops the entry for one page/width, reporting it to the eviction listener. */
    V remove(int pageIndex, int targetWidthPx) {
        return cache.remove(keyFor(pageIndex, targetWidthPx));
    }

    /** Shrinks the cache to at most {@code keepFraction} of its byte budget. */
    void trimToFraction(float keepFraction) {
        cache.trimToSize(Math.max(1, (int) (cache.maxSizeBytes() * keepFraction)));
    }

    /** Drops every cached page; later gets return null. Idempotent. */
    void release() {
        released = true;
        cache.clear();
    }

    private BackgroundCacheKey keyFor(int pageIndex, int targetWidthPx) {
        return new BackgroundCacheKey(documentId, pageIndex,
                BackgroundCacheKey.quantizeWidth(targetWidthPx));
    }
}
