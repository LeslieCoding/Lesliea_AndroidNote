package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;
import android.util.SizeF;

import com.leslie.colorosnote.core.common.CacheTrimRegistry;
import com.leslie.colorosnote.document.pdf.PdfEngine;

/**
 * {@link PageBackgroundProvider} backed by a {@link PdfEngine}: renders the
 * requested PDF page at the nearest width tier (height from the page's aspect
 * ratio in points) and keeps the results in an LRU memory cache.
 *
 * <p>Note that {@code PdfEngine} implementations typically keep their own page
 * cache and recycle evicted bitmaps; this provider detects recycled hits and
 * re-renders instead of handing out dead bitmaps.
 */
public final class PdfBackgroundProvider implements PageBackgroundProvider {

    private final PdfEngine engine;
    private final BackgroundRenderCache<Bitmap> cache;
    private final CacheTrimRegistry.TrimTarget trimTarget;

    /** Default cache budget: 1/8 of the VM max heap. */
    public PdfBackgroundProvider(String documentId, PdfEngine engine) {
        this(documentId, engine, PageBackgroundProvider.defaultCacheBytes());
    }

    public PdfBackgroundProvider(String documentId, PdfEngine engine, int cacheBytes) {
        this.engine = engine;
        this.cache = new BackgroundRenderCache<>(documentId, engine::pageCount,
                this::renderPage, cacheBytes,
                (key, bmp) -> bmp == null ? 0 : bmp.getByteCount(),
                (key, bmp) -> {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                });
        this.trimTarget = cache::trimToFraction;
        CacheTrimRegistry.register(trimTarget);
    }

    private Bitmap renderPage(BackgroundCacheKey key) {
        SizeF sizePt = engine.pageSizePt(key.pageIndex);
        int heightPx = Math.max(1,
                Math.round(key.widthPx * sizePt.getHeight() / sizePt.getWidth()));
        return engine.renderPage(key.pageIndex, key.widthPx, heightPx);
    }

    @Override
    public String documentId() {
        return cache.documentId();
    }

    @Override
    public int pageCount() {
        return cache.pageCount();
    }

    @Override
    public Bitmap getPageBitmap(int pageIndex, int targetWidthPx) {
        Bitmap bmp = cache.get(pageIndex, targetWidthPx);
        if (bmp != null && bmp.isRecycled()) {
            // The engine evicted and recycled its own copy behind our back:
            // drop the dead entry and render again.
            cache.remove(pageIndex, targetWidthPx);
            bmp = cache.get(pageIndex, targetWidthPx);
        }
        return bmp;
    }

    @Override
    public void release() {
        CacheTrimRegistry.unregister(trimTarget);
        cache.release();
    }
}
