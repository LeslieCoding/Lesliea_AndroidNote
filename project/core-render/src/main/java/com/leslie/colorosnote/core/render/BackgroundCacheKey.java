package com.leslie.colorosnote.core.render;

import java.util.Objects;

/**
 * Identity of one cached document-background bitmap: source document, 0-based
 * page index and the quantized pixel width the page was rendered at.
 *
 * <p>Widths snap upward to a fixed tier so small viewport changes (fit-width
 * vs. slight zoom) reuse the same cached page instead of flooding the cache
 * with near-duplicate renders. Pure data, Android-free, so the key logic is
 * unit-testable on the JVM.
 */
public final class BackgroundCacheKey {

    /** Width quantization step in px: render widths round up to a multiple. */
    public static final int WIDTH_TIER_PX = 256;

    public final String documentId;
    public final int pageIndex;
    /** Quantized render width in px (a multiple of {@link #WIDTH_TIER_PX}). */
    public final int widthPx;

    public BackgroundCacheKey(String documentId, int pageIndex, int widthPx) {
        if (documentId == null) {
            throw new NullPointerException("documentId must not be null");
        }
        this.documentId = documentId;
        this.pageIndex = pageIndex;
        this.widthPx = widthPx;
    }

    /** Rounds {@code widthPx} up to the next width tier; non-positive input clamps to one tier. */
    public static int quantizeWidth(int widthPx) {
        if (widthPx <= 0) {
            return WIDTH_TIER_PX;
        }
        return ((widthPx + WIDTH_TIER_PX - 1) / WIDTH_TIER_PX) * WIDTH_TIER_PX;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BackgroundCacheKey)) {
            return false;
        }
        BackgroundCacheKey k = (BackgroundCacheKey) o;
        return pageIndex == k.pageIndex && widthPx == k.widthPx
                && documentId.equals(k.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, pageIndex, widthPx);
    }

    @Override
    public String toString() {
        return "BackgroundCacheKey{" + documentId + "#" + pageIndex + "@" + widthPx + "px}";
    }
}
