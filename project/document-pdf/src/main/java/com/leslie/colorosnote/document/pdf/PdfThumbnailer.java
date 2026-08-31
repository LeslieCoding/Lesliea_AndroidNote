package com.leslie.colorosnote.document.pdf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Low-resolution page thumbnail generator (~200 px wide) with a two-level cache:
 * a small in-memory LRU and a directory-backed disk LRU trimmed by total bytes.
 *
 * <p>The disk cache directory is injected by the caller (typically a per-document
 * subfolder of the app cache dir). Files are named {@code <sha1(docKey)>_<page>.png}
 * and trimmed least-recently-touched first.
 */
public final class PdfThumbnailer {

    private static final String TAG = "PdfThumbnailer";

    /** Default thumbnail width in pixels; height follows the page aspect ratio. */
    public static final int DEFAULT_WIDTH_PX = 200;

    /** Default disk budget: 16 MB (~400 typical 200px PNG pages). */
    public static final long DEFAULT_DISK_BYTES = 16L * 1024 * 1024;

    private static final int MEM_CACHE_BYTES = 4 * 1024 * 1024;

    private final File cacheDir;
    private final long maxDiskBytes;
    private final LruMemoryCache<String, Bitmap> memCache;

    public PdfThumbnailer(@NonNull File cacheDir) {
        this(cacheDir, DEFAULT_DISK_BYTES);
    }

    public PdfThumbnailer(@NonNull File cacheDir, long maxDiskBytes) {
        this.cacheDir = cacheDir;
        this.maxDiskBytes = maxDiskBytes;
        this.memCache = new LruMemoryCache<>(MEM_CACHE_BYTES,
                (key, bitmap) -> bitmap == null ? 0 : bitmap.getByteCount(),
                (key, bitmap) -> {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    /**
     * Returns a ~{@value #DEFAULT_WIDTH_PX}px-wide thumbnail for {@code page} of the
     * document identified by {@code docKey}, or null when the page cannot be rendered.
     *
     * <p>OWNERSHIP: the returned Bitmap is owned by this cache; callers must not
     * recycle it.
     */
    @WorkerThread
    @Nullable
    public Bitmap thumbnail(@NonNull PdfEngine engine, @NonNull String docKey, int page) {
        String key = docKey + ':' + page;
        Bitmap hit = memCache.get(key);
        if (hit != null && !hit.isRecycled()) {
            return hit;
        }

        File file = diskFile(docKey, page);
        Bitmap decoded = decodeDisk(file);
        if (decoded != null) {
            memCache.put(key, decoded);
            return decoded;
        }

        Bitmap rendered = render(engine, page);
        if (rendered == null) {
            return null;
        }
        try {
            writeDisk(file, rendered);
        } catch (IOException e) {
            AppLogger.w(TAG, "Failed to cache thumbnail on disk (page " + page + ")", e);
        }
        memCache.put(key, rendered);
        return rendered;
    }

    /** Drops all in-memory and on-disk thumbnails. */
    @WorkerThread
    public void clear() {
        memCache.clear();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    @Nullable
    private Bitmap render(PdfEngine engine, int page) {
        try {
            SizeF sizePt = engine.pageSizePt(page);
            if (sizePt.getWidth() <= 0 || sizePt.getHeight() <= 0) {
                return null;
            }
            int widthPx = DEFAULT_WIDTH_PX;
            int heightPx = Math.max(1, Math.round(widthPx * sizePt.getHeight() / sizePt.getWidth()));
            return engine.renderPage(page, widthPx, heightPx);
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "Thumbnail render failed (page " + page + ")", e);
            return null;
        }
    }

    @Nullable
    private Bitmap decodeDisk(File file) {
        if (!file.isFile()) {
            return null;
        }
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) {
            // Corrupt cache entry: drop it.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        // Touch so the disk LRU sees real recency.
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
        return bmp;
    }

    private void writeDisk(File file, Bitmap bmp) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File tmp = new File(cacheDir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Bitmap.compress returned false");
            }
        }
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed: " + tmp + " -> " + file);
        }
        trimDiskCache();
    }

    /** Deletes least-recently-touched entries until the directory fits the budget. */
    private void trimDiskCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= maxDiskBytes) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= maxDiskBytes) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
            }
        }
    }

    private File diskFile(String docKey, int page) {
        return new File(cacheDir, sha1Hex(docKey) + '_' + page + ".png");
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed by the JCA spec; fall back to a plain hash anyway.
            return Integer.toHexString(s.hashCode());
        }
    }
}
