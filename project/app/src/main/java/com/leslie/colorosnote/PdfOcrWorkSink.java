package com.leslie.colorosnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.SizeF;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.storage.container.InknoteReader;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;
import com.leslie.colorosnote.document.pdf.PdfEngine;
import com.leslie.colorosnote.feature.importer.AndroidPdfEngineFactory;
import com.leslie.colorosnote.feature.importer.OcrWorkSink;
import com.leslie.colorosnote.feature.ocr.OcrCoordinator;
import com.leslie.colorosnote.feature.ocr.OcrIndexEntry;
import com.leslie.colorosnote.feature.ocr.OcrOptions;
import com.leslie.colorosnote.feature.search.SearchIndexScheduler;

import java.io.File;
import java.nio.file.Files;

/**
 * Deferred OCR for imported scanned PDFs (spec 36 OCR stage, background).
 * Reopens the persisted {@code resources/source.pdf} from the document
 * container, renders each page at 2x, runs the OCR coordinator and feeds the
 * results into the search index. Never blocks the import pipeline.
 */
final class PdfOcrWorkSink implements OcrWorkSink {

    private static final String TAG = "PdfOcrWorkSink";
    private static final String CONTAINER_PDF = "resources/source.pdf";
    private static final float RENDER_SCALE = 2f;

    private final Context app;
    private final SearchIndexScheduler scheduler;
    private final OcrCoordinator ocr;

    PdfOcrWorkSink(Context app, SearchIndexScheduler scheduler, OcrCoordinator ocr) {
        this.app = app.getApplicationContext();
        this.scheduler = scheduler;
        this.ocr = ocr;
    }

    @Override
    public void schedulePdfOcr(String documentId, int pageCount) {
        AppExecutors.get().io().execute(() -> run(documentId));
    }

    private void run(String documentId) {
        File tmp = null;
        PdfEngine engine = null;
        try {
            DocumentRepository repository = AppServices.repository(app);
            File container = repository.fileFor(documentId);
            byte[] pdfBytes = new InknoteReader().readEntry(container, CONTAINER_PDF);
            if (pdfBytes == null || pdfBytes.length == 0) {
                AppLogger.w(TAG, "no embedded pdf for " + documentId);
                return;
            }
            NoteDocument doc = repository.openDocument(documentId);

            tmp = File.createTempFile("ocr_", ".pdf", app.getCacheDir());
            Files.write(tmp.toPath(), pdfBytes);
            engine = new AndroidPdfEngineFactory().open(tmp);

            OcrOptions options = OcrOptions.defaults();
            int pages = Math.min(engine.pageCount(), doc.pages.size());
            for (int i = 0; i < pages; i++) {
                SizeF size = engine.pageSizePt(i);
                int w = Math.max(1, Math.round(size.getWidth() * RENDER_SCALE));
                int h = Math.max(1, Math.round(size.getHeight() * RENDER_SCALE));
                Bitmap pageImage = engine.renderPage(i, w, h);
                String pageId = doc.pages.get(i).id;
                try {
                    OcrCoordinator.OcrOutcome outcome =
                            ocr.runForIndexing(pageImage, documentId, pageId, 0, options);
                    if (outcome.entries != null) {
                        for (OcrIndexEntry entry : outcome.entries) {
                            scheduler.indexOcr(documentId, pageId, entry.text,
                                    entry.lowConfidence);
                        }
                    }
                } finally {
                    pageImage.recycle();
                }
            }
            AppLogger.i(TAG, "OCR done for " + documentId + ", pages=" + pages);
        } catch (Exception e) {
            AppLogger.e(TAG, "deferred OCR failed for " + documentId, e);
        } finally {
            if (engine != null) {
                engine.close();
            }
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }
}
