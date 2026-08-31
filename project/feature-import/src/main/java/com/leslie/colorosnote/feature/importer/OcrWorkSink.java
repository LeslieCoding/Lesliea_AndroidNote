package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;

/**
 * Hook for deferred OCR of imported scanned PDFs (spec 36 OCR stage:
 * 后台执行). The import pipeline never blocks on bitmap OCR; when
 * {@code OcrCoordinator.plan} decides a document is textless, the coordinator
 * reports it here after COMMIT. The implementor (feature-ocr side) reopens the
 * persisted PDF from the document container and runs recognition on the
 * low-priority background executor.
 */
public interface OcrWorkSink {

    void schedulePdfOcr(@NonNull String documentId, int pageCount);
}
