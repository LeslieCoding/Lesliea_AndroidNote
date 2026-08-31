package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.model.Page;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downsampled page thumbnails with an LRU cache. Rendering runs on the
 * low-priority background executor (never competes with the ink pipeline);
 * callbacks are posted to the main thread.
 *
 * <p>Thread-safety: the caller must pass a Page whose strokes/objects are not
 * being mutated while the render is queued (e.g. a document snapshot).
 */
public final class ThumbnailRenderer {

    /** Delivers the rendered (or cached) thumbnail on the main thread. */
    public interface Callback {
        void onThumbnail(String pageId, Bitmap bitmap);
    }

    private static final long DEFAULT_BUDGET_BYTES = 8L * 1024 * 1024;

    private final PageRenderer renderer = new PageRenderer();
    private final long budgetBytes;
    private long cacheBytes;
    private final LinkedHashMap<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (cacheBytes > budgetBytes) {
                        cacheBytes -= byteSize(eldest.getValue());
                        eldest.getValue().recycle();
                        return true;
                    }
                    return false;
                }
            };

    public ThumbnailRenderer() {
        this(DEFAULT_BUDGET_BYTES);
    }

    public ThumbnailRenderer(long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    /** Returns a cached thumbnail or null. */
    public Bitmap getCached(String pageId, int maxWidthPx) {
        return cache.get(key(pageId, maxWidthPx));
    }

    /**
     * Forwards the document background provider (PDF/OFFICE imports) to the
     * internal {@link PageRenderer}. Bound to one document: set to null when
     * the editor leaves the document, and {@link #clear()} the cache whenever
     * the provider changes so stale thumbnails are re-rendered.
     */
    public void setPageBackgroundProvider(PageBackgroundProvider provider) {
        renderer.setPageBackgroundProvider(provider);
    }

    /** Renders (or serves from cache) a thumbnail fitting maxWidthPx. */
    public void request(Page page, int maxWidthPx, Callback callback) {
        String key = key(page.id, maxWidthPx);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            callback.onThumbnail(page.id, cached);
            return;
        }
        AppExecutors.get().background().execute(() -> {
            Bitmap existing = cache.get(key);
            if (existing != null && !existing.isRecycled()) {
                postResult(callback, page.id, existing);
                return;
            }
            float scale = maxWidthPx / page.width;
            int w = Math.max(1, maxWidthPx);
            int h = Math.max(1, Math.round(page.height * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(scale, scale);
            renderer.drawPage(canvas, page);
            cache.put(key, bmp);
            cacheBytes += byteSize(bmp);
            postResult(callback, page.id, bmp);
        });
    }

    /** Evicts and recycles everything. */
    public void clear() {
        for (Bitmap bmp : cache.values()) {
            bmp.recycle();
        }
        cache.clear();
        cacheBytes = 0;
    }

    private void postResult(Callback callback, String pageId, Bitmap bmp) {
        AppExecutors.get().mainThread().post(() -> callback.onThumbnail(pageId, bmp));
    }

    private static String key(String pageId, int maxWidthPx) {
        return pageId + "@" + maxWidthPx;
    }

    private static int byteSize(Bitmap bmp) {
        return bmp.getWidth() * bmp.getHeight() * 4;
    }
}
