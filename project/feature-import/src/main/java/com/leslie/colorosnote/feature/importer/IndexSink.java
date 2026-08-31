package com.leslie.colorosnote.feature.importer;

import androidx.annotation.NonNull;

/**
 * Hand-off of extracted import text to the search index (spec 36 INDEX stage).
 * feature-search injects an adapter backed by {@code SearchIndexScheduler};
 * when no sink is installed the INDEX stage is a logged no-op.
 */
public interface IndexSink {

    /** Text provenance, mirroring {@code SearchResultMerger}'s source segments. */
    enum Source {
        PDF_TEXT,
        OFFICE_TEXT,
        OCR,
        FILE_NAME
    }

    /**
     * @param pageId page the text belongs to, or "" for document-level segments
     */
    void index(@NonNull String documentId, @NonNull String pageId,
               @NonNull Source source, boolean lowConfidence, @NonNull String text);
}
