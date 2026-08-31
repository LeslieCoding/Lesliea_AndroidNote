package com.leslie.colorosnote;

import android.content.Context;

import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;
import com.leslie.colorosnote.document.caj.DefaultCajImportEngine;
import com.leslie.colorosnote.feature.importer.AndroidImportMedia;
import com.leslie.colorosnote.feature.importer.AndroidPdfEngineFactory;
import com.leslie.colorosnote.feature.importer.ContentResolverSourceResolver;
import com.leslie.colorosnote.feature.importer.CoordinatorOfficeImporter;
import com.leslie.colorosnote.feature.importer.ImportCoordinator;
import com.leslie.colorosnote.feature.importer.ImportJobManager;
import com.leslie.colorosnote.feature.importer.IndexSink;
import com.leslie.colorosnote.feature.importer.RepositoryDocumentStore;
import com.leslie.colorosnote.feature.ocr.MlKitOcrEngine;
import com.leslie.colorosnote.feature.ocr.OcrCoordinator;
import com.leslie.colorosnote.feature.search.SearchIndexScheduler;

import java.io.File;

/**
 * Assembles the import pipeline (feature-import) with production adapters and
 * the search-index / OCR sinks, as a lazy app-wide singleton.
 */
public final class AppImports {

    private static volatile ImportJobManager manager;

    private AppImports() {
    }

    public static ImportJobManager manager(Context context) {
        if (manager == null) {
            synchronized (AppImports.class) {
                if (manager == null) {
                    manager = build(context.getApplicationContext());
                }
            }
        }
        return manager;
    }

    private static ImportJobManager build(Context app) {
        InkNoteDatabase db = AppServices.database(app);
        DocumentRepository repository = AppServices.repository(app);
        SearchIndexScheduler scheduler = new SearchIndexScheduler(db.indexDao());
        OcrCoordinator ocr = new OcrCoordinator(new MlKitOcrEngine());

        IndexSink indexSink = (documentId, pageId, source, lowConfidence, text) -> {
            switch (source) {
                case PDF_TEXT:
                    scheduler.indexPdfText(documentId, pageId, text);
                    break;
                case OFFICE_TEXT:
                    scheduler.indexOfficeText(documentId, pageId, text);
                    break;
                case OCR:
                    scheduler.indexOcr(documentId, pageId, text, lowConfidence);
                    break;
                case FILE_NAME:
                default:
                    scheduler.indexFileName(documentId, text);
                    break;
            }
        };

        ImportCoordinator coordinator = new ImportCoordinator(
                new ContentResolverSourceResolver(app.getContentResolver()),
                new AndroidPdfEngineFactory(),
                new CoordinatorOfficeImporter(),
                new DefaultCajImportEngine(),
                ocr,
                new RepositoryDocumentStore(repository),
                new AndroidImportMedia(),
                new File(app.getCacheDir(), "import_sandbox"),
                new File(app.getFilesDir(), "thumbnails"),
                indexSink,
                new PdfOcrWorkSink(app, scheduler, ocr));
        return new ImportJobManager(coordinator);
    }
}
