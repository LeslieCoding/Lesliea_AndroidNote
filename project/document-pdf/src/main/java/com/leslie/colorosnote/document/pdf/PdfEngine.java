package com.leslie.colorosnote.document.pdf;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Engine abstraction for reading and rendering PDF pages.
 *
 * <p>Completely decoupled from the annotation layer: the editor only ever sees
 * Bitmaps, page sizes in points and extracted text, so the implementation can be
 * swapped for a commercial PDF SDK without touching editor code.
 *
 * <p>Threading contract: all methods are blocking and must be called off the main
 * thread unless stated otherwise. Implementations must be internally thread-safe.
 */
public interface PdfEngine {

    /** Takes ownership of {@code pfd} (closed by {@link #close()}). */
    @WorkerThread
    void open(@NonNull ParcelFileDescriptor pfd) throws IOException;

    /** Convenience: opens a read-only descriptor for the given content Uri. */
    @WorkerThread
    void open(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException;

    /** Releases the document, all cached pages and the underlying descriptor. Idempotent. */
    @WorkerThread
    void close();

    boolean isOpen();

    /** Number of pages, or 0 when closed. */
    @WorkerThread
    int pageCount();

    /** Page size in PDF points (1/72 inch), rotation attribute not applied. */
    @WorkerThread
    @NonNull
    SizeF pageSizePt(int page);

    /** Renders a full page into a new Bitmap of exactly {@code widthPx} x {@code heightPx}. */
    @WorkerThread
    @NonNull
    Bitmap renderPage(int page, int widthPx, int heightPx);

    /**
     * Renders a full page, rotated clockwise by {@code displayRotationDegrees}
     * (0/90/180/270) on top of the PDF's own rotation attribute.
     */
    @WorkerThread
    @NonNull
    Bitmap renderPage(int page, int widthPx, int heightPx, int displayRotationDegrees);

    /**
     * Renders a clip of the page. {@code regionPt} is in page points (top-left
     * origin, same coordinate space as {@link #pageSizePt}); the output bitmap is
     * {@code round(region * scale)} pixels large.
     */
    @WorkerThread
    @NonNull
    Bitmap renderPageRegion(int page, @NonNull RectF regionPt, float scale);

    /**
     * Extracts the raw text of a page, or an empty string when the engine cannot
     * extract text (see {@link #supportsTextExtraction()}) or the page has none.
     */
    @WorkerThread
    @NonNull
    String extractText(int page);

    /**
     * Whether this engine can extract text at all. When false, the text-layer
     * state of every page is unknown and {@link #extractText(int)} always returns "".
     */
    boolean supportsTextExtraction();

    /** Full-text search. Optional: default implementation finds nothing. */
    @WorkerThread
    @NonNull
    default List<String> searchText(@NonNull String query) {
        return Collections.emptyList();
    }

    /** Document outline / table of contents. Optional: default is an empty list. */
    @WorkerThread
    @NonNull
    default List<OutlineEntry> outline() {
        return Collections.emptyList();
    }

    /** One entry of the document outline (a tree via {@link #children}). */
    final class OutlineEntry {
        @NonNull
        public final String title;
        /** 0-based target page index; -1 when the destination is unknown. */
        public final int pageIndex;
        @NonNull
        public final List<OutlineEntry> children;

        public OutlineEntry(@NonNull String title, int pageIndex) {
            this(title, pageIndex, new ArrayList<>());
        }

        public OutlineEntry(@NonNull String title, int pageIndex, @Nullable List<OutlineEntry> children) {
            this.title = title;
            this.pageIndex = pageIndex;
            this.children = children == null ? new ArrayList<>() : children;
        }
    }
}
