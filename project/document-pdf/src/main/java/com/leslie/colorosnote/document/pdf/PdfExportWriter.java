package com.leslie.colorosnote.document.pdf;

import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.Result;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * Writes flattened PDFs via {@link android.graphics.pdf.PdfDocument}: each output
 * page is a Canvas on which the caller paints base page bitmap + vector annotations.
 *
 * <p>The writer owns layout only: page size in points → pixel dimensions at the
 * requested DPI. All content (base page render, ink strokes, highlights, text
 * boxes, images, stickers, shapes, tape) is painted by the supplied
 * {@link PagePainter}, so the export stays decoupled from the annotation model.
 *
 * <p>Typical use:
 * <pre>{@code
 * PdfExportWriter writer = new PdfExportWriter();
 * Result<File> r = writer.export(outFile, engine.pageCount(),
 *         engine::pageSizePt,
 *         (canvas, page, w, h) -> {
 *             canvas.drawBitmap(engine.renderPage(page, w, h), 0, 0, null);
 *             annotationPainter.paint(canvas, page, w, h);
 *         },
 *         new PdfExportWriter.Options().setDpi(200), null, null);
 * }</pre>
 */
public final class PdfExportWriter {

    private static final String TAG = "PdfExportWriter";

    /** Default export resolution. 150 dpi ≈ screen quality; use 300 for print. */
    public static final int DEFAULT_DPI = 150;

    /** Paints one output page. The canvas is white and sized widthPx × heightPx. */
    public interface PagePainter {
        void paint(@NonNull Canvas canvas, int pageIndex, int widthPx, int heightPx);
    }

    /** Provides the size of each source page in PDF points (1/72 inch). */
    public interface PageSizeProvider {
        @NonNull
        SizeF pageSizePt(int pageIndex);
    }

    /** Called on the worker thread after each finished page. */
    public interface ProgressCallback {
        void onPageFinished(int pageIndex, int finishedCount, int totalCount);
    }

    /** Cooperative cancellation; polled before every page. */
    public interface CancelSignal {
        boolean isCancelled();
    }

    /** Export parameters. All setters return {@code this} for chaining. */
    public static final class Options {
        private int dpi = DEFAULT_DPI;
        private int fromPage = 0;
        /** Inclusive; -1 means "last page". */
        private int toPage = -1;

        public int getDpi() {
            return dpi;
        }

        public Options setDpi(int dpi) {
            if (dpi < 36 || dpi > 600) {
                throw new IllegalArgumentException("dpi out of sane range [36,600]: " + dpi);
            }
            this.dpi = dpi;
            return this;
        }

        public int getFromPage() {
            return fromPage;
        }

        public int getToPage() {
            return toPage;
        }

        /** Limits export to [fromPage, toPage], both inclusive. toPage -1 = last page. */
        public Options setPageRange(int fromPage, int toPage) {
            if (fromPage < 0) {
                throw new IllegalArgumentException("fromPage must be >= 0");
            }
            this.fromPage = fromPage;
            this.toPage = toPage;
            return this;
        }
    }

    /**
     * Runs the export synchronously; call from a background thread.
     *
     * <p>Writes to a {@code .tmp} sibling first and renames on success, so a crash
     * or cancellation never leaves a truncated file at {@code outFile}.
     *
     * @return {@code Result.success(outFile)} on success, or a failure wrapping
     *         {@link CancellationException} / {@link IOException} / {@link RuntimeException}.
     */
    @WorkerThread
    @NonNull
    public Result<File> export(@NonNull File outFile,
                               int totalPages,
                               @NonNull PageSizeProvider pageSizes,
                               @NonNull PagePainter painter,
                               @Nullable Options options,
                               @Nullable ProgressCallback progress,
                               @Nullable CancelSignal cancel) {
        Options opts = options == null ? new Options() : options;
        if (totalPages <= 0) {
            return Result.failure(new IllegalArgumentException("totalPages must be > 0"));
        }
        int from = Math.max(0, opts.getFromPage());
        int to = opts.getToPage() < 0 ? totalPages - 1 : Math.min(opts.getToPage(), totalPages - 1);
        if (from > to) {
            return Result.failure(new IllegalArgumentException(
                    "empty page range [" + from + "," + to + "] of " + totalPages + " pages"));
        }

        File parent = outFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(outFile.getAbsolutePath() + ".tmp");

        PdfDocument doc = new PdfDocument();
        int planned = to - from + 1;
        int finished = 0;
        try {
            for (int page = from; page <= to; page++) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new CancellationException("PDF export cancelled before page " + page);
                }
                SizeF sizePt = pageSizes.pageSizePt(page);
                int widthPx = ptToPx(sizePt.getWidth(), opts.getDpi());
                int heightPx = ptToPx(sizePt.getHeight(), opts.getDpi());

                // Page numbers in the output document are 1-based and sequential.
                PdfDocument.PageInfo info = new PdfDocument.PageInfo
                        .Builder(widthPx, heightPx, finished + 1)
                        .create();
                PdfDocument.Page outPage = doc.startPage(info);
                try {
                    painter.paint(outPage.getCanvas(), page, widthPx, heightPx);
                } finally {
                    doc.finishPage(outPage);
                }
                finished++;
                if (progress != null) {
                    progress.onPageFinished(page, finished, planned);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                doc.writeTo(fos);
                fos.getFD().sync();
            }
        } catch (IOException | RuntimeException e) {
            AppLogger.e(TAG, "PDF export failed", e);
            return Result.failure(e);
        } finally {
            doc.close();
        }

        if (outFile.exists() && !outFile.delete()) {
            AppLogger.w(TAG, "Could not replace existing export target");
        }
        if (!tmp.renameTo(outFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return Result.failure(new IOException("rename failed: " + tmp + " -> " + outFile));
        }
        AppLogger.i(TAG, "PDF export done: " + finished + " pages at " + opts.getDpi() + " dpi");
        return Result.success(outFile);
    }

    /** Converts points (1/72 inch) to pixels at the given dpi, rounding to nearest. */
    static int ptToPx(float pt, int dpi) {
        return Math.max(1, Math.round(pt * dpi / 72f));
    }
}
