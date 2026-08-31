package com.leslie.colorosnote.document.pdf;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;

import java.io.IOException;
import java.util.Objects;

/**
 * {@link PdfEngine} backed by the platform {@link PdfRenderer}.
 *
 * <p>Notes on the platform API's limits:
 * <ul>
 *   <li>{@code PdfRenderer} is NOT thread-safe — every access is serialized on an
 *       internal lock.</li>
 *   <li>It cannot extract text or outlines: {@link #extractText(int)} returns ""
 *       and {@link #supportsTextExtraction()} returns false, meaning the text-layer
 *       state is unknown (a future engine can provide the real answer).</li>
 *   <li>It ignores the page rotation attribute, so callers pass their display
 *       rotation explicitly.</li>
 * </ul>
 *
 * <p>Rendered pages are kept in an LRU memory cache (budget ~32 MB by default).
 * OWNERSHIP: Bitmaps returned by render methods are owned by this engine and may be
 * recycled when evicted from the cache. Callers must never recycle them and must
 * not hold them across {@link #close()}.
 */
public final class AndroidPdfEngine implements PdfEngine {

    private static final String TAG = "AndroidPdfEngine";

    /** Default page-bitmap cache budget: 32 MB. */
    public static final int DEFAULT_CACHE_BYTES = 32 * 1024 * 1024;

    /** Serializes every access to {@link #renderer}; also guards open/close state. */
    private final Object lock = new Object();

    private final LruMemoryCache<PageKey, Bitmap> pageCache;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;

    public AndroidPdfEngine() {
        this(DEFAULT_CACHE_BYTES);
    }

    public AndroidPdfEngine(int cacheBytes) {
        pageCache = new LruMemoryCache<>(cacheBytes,
                (key, bitmap) -> bitmap == null ? 0 : bitmap.getByteCount(),
                (key, bitmap) -> {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    @WorkerThread
    @Override
    public void open(@NonNull ParcelFileDescriptor pfd) throws IOException {
        synchronized (lock) {
            closeLocked();
            try {
                renderer = new PdfRenderer(pfd);
                descriptor = pfd;
            } catch (RuntimeException e) {
                // PdfRenderer throws RuntimeException for corrupt/encrypted files.
                try {
                    pfd.close();
                } catch (IOException ignored) {
                    // nothing useful to add
                }
                throw new IOException("Failed to open PDF document", e);
            }
        }
    }

    @WorkerThread
    @Override
    public void open(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) {
            throw new IOException("ContentResolver returned null descriptor for " + uri);
        }
        open(pfd);
    }

    @WorkerThread
    @Override
    public void close() {
        synchronized (lock) {
            closeLocked();
        }
        // Recycling must happen outside the renderer lock-free section is fine;
        // clear() is synchronized on the cache itself.
        pageCache.clear();
    }

    private void closeLocked() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
                AppLogger.w(TAG, "Failed to close PDF descriptor", e);
            }
            descriptor = null;
        }
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) {
            return renderer != null;
        }
    }

    @WorkerThread
    @Override
    public int pageCount() {
        synchronized (lock) {
            return renderer == null ? 0 : renderer.getPageCount();
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public SizeF pageSizePt(int page) {
        synchronized (lock) {
            PdfRenderer.Page p = openPageLocked(page);
            try {
                return new SizeF(p.getWidth(), p.getHeight());
            } finally {
                p.close();
            }
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public Bitmap renderPage(int page, int widthPx, int heightPx) {
        return renderPage(page, widthPx, heightPx, 0);
    }

    @WorkerThread
    @Override
    @NonNull
    public Bitmap renderPage(int page, int widthPx, int heightPx, int displayRotationDegrees) {
        int rotation = normalizeRotation(displayRotationDegrees);
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("widthPx/heightPx must be > 0");
        }
        PageKey key = new PageKey(page, widthPx, heightPx, rotation);
        Bitmap cached = pageCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap bitmap;
        synchronized (lock) {
            checkOpenLocked();
            // Double-check under the lock: another thread may have rendered meanwhile.
            Bitmap again = pageCache.get(key);
            if (again != null && !again.isRecycled()) {
                return again;
            }
            bitmap = renderPageLocked(page, widthPx, heightPx, rotation);
            pageCache.put(key, bitmap);
        }
        return bitmap;
    }

    /** Caller must hold {@link #lock}. */
    private Bitmap renderPageLocked(int page, int widthPx, int heightPx, int rotation) {
        PdfRenderer.Page p = openPageLocked(page);
        try {
            if (rotation == 0) {
                Bitmap out = newWhiteBitmap(widthPx, heightPx);
                p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return out;
            }
            // Render unrotated into a swapped-dimension buffer, then rotate via Matrix.
            boolean quarter = rotation == 90 || rotation == 270;
            int srcW = quarter ? heightPx : widthPx;
            int srcH = quarter ? widthPx : heightPx;
            Bitmap raw = newWhiteBitmap(srcW, srcH);
            p.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            Matrix m = new Matrix();
            m.postRotate(rotation, srcW / 2f, srcH / 2f);
            m.postTranslate((widthPx - srcW) / 2f, (heightPx - srcH) / 2f);
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, srcW, srcH, m, true);
            raw.recycle();
            return out;
        } finally {
            p.close();
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public Bitmap renderPageRegion(int page, @NonNull RectF regionPt, float scale) {
        if (scale <= 0f) {
            throw new IllegalArgumentException("scale must be > 0");
        }
        if (regionPt.isEmpty()) {
            throw new IllegalArgumentException("regionPt must be non-empty");
        }
        synchronized (lock) {
            checkOpenLocked();
            PdfRenderer.Page p = openPageLocked(page);
            try {
                int widthPx = Math.max(1, Math.round(regionPt.width() * scale));
                int heightPx = Math.max(1, Math.round(regionPt.height() * scale));
                Bitmap out = newWhiteBitmap(widthPx, heightPx);
                // Map page points to pixels: translate the region origin to (0,0),
                // scaled. Matrix ops compose last-first, so scale then translate.
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postTranslate(-regionPt.left * scale, -regionPt.top * scale);
                p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                return out;
            } finally {
                p.close();
            }
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public String extractText(int page) {
        // PdfRenderer exposes no text API; text-layer state stays unknown.
        return "";
    }

    @Override
    public boolean supportsTextExtraction() {
        return false;
    }

    private static Bitmap newWhiteBitmap(int widthPx, int heightPx) {
        Bitmap bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        // White background: PDF pages are not opaque by definition.
        bmp.eraseColor(Color.WHITE);
        return bmp;
    }

    private PdfRenderer.Page openPageLocked(int page) {
        checkOpenLocked();
        if (page < 0 || page >= renderer.getPageCount()) {
            throw new IndexOutOfBoundsException(
                    "page " + page + " out of range [0," + renderer.getPageCount() + ")");
        }
        return renderer.openPage(page);
    }

    private void checkOpenLocked() {
        if (renderer == null) {
            throw new IllegalStateException("Engine is not open");
        }
    }

    private static int normalizeRotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        if (r % 90 != 0) {
            throw new IllegalArgumentException("rotation must be a multiple of 90: " + degrees);
        }
        return r;
    }

    /** Identity of one cached render: page + output size + display rotation. */
    private static final class PageKey {
        final int page;
        final int widthPx;
        final int heightPx;
        final int rotation;

        PageKey(int page, int widthPx, int heightPx, int rotation) {
            this.page = page;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.rotation = rotation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PageKey)) {
                return false;
            }
            PageKey k = (PageKey) o;
            return page == k.page && widthPx == k.widthPx && heightPx == k.heightPx && rotation == k.rotation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(page, widthPx, heightPx, rotation);
        }
    }
}
