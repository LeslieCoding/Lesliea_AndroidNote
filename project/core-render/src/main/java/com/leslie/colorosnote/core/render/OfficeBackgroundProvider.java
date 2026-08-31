package com.leslie.colorosnote.core.render;

import android.graphics.Bitmap;

import com.leslie.colorosnote.core.common.CacheTrimRegistry;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.ImportedPage;

/**
 * {@link PageBackgroundProvider} backed by an {@link ImportedDocument}: draws
 * the page's draw commands onto a Bitmap at the nearest width tier (via
 * {@link OfficePagePainter}) and keeps the results in an LRU memory cache.
 */
public final class OfficeBackgroundProvider implements PageBackgroundProvider {

    private final ImportedDocument document;
    private final BackgroundRenderCache<Bitmap> cache;
    private final CacheTrimRegistry.TrimTarget trimTarget;

    /** Default cache budget: 1/8 of the VM max heap. */
    public OfficeBackgroundProvider(String documentId, ImportedDocument document) {
        this(documentId, document, PageBackgroundProvider.defaultCacheBytes());
    }

    public OfficeBackgroundProvider(String documentId, ImportedDocument document, int cacheBytes) {
        this.document = document;
        this.cache = new BackgroundRenderCache<>(documentId, () -> document.getPages().size(),
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
        ImportedPage page = document.getPages().get(key.pageIndex);
        return OfficePagePainter.render(page, document, key.widthPx);
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
        return cache.get(pageIndex, targetWidthPx);
    }

    @Override
    public void release() {
        CacheTrimRegistry.unregister(trimTarget);
        cache.release();
    }
}
