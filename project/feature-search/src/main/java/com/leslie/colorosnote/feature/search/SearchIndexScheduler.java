package com.leslie.colorosnote.feature.search;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.search.NGramTokenizer;
import com.leslie.colorosnote.core.search.TextNormalizer;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;
import com.leslie.colorosnote.core.storage.db.entity.SearchIndexEntity;

import java.util.List;

/**
 * Incremental search-index entry point (spec 22/35). Called when a text box is
 * edited, OCR / handwriting recognition / Office import / transcript finishes.
 * Each call replaces exactly one document+page+source segment via
 * {@link IndexDao#replaceSearchSegment} — the index is never rebuilt.
 * All work runs on the low-priority background executor so indexing never
 * competes with the real-time ink pipeline.
 */
public final class SearchIndexScheduler {

    private static final String TAG = "SearchIndex";

    private final IndexDao indexDao;

    public SearchIndexScheduler(IndexDao indexDao) {
        this.indexDao = indexDao;
    }

    /** Text box edited. */
    public void indexTextBox(String documentId, String pageId, String text) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_TEXT_BOX, false, text);
    }

    /** PDF native text extracted. */
    public void indexPdfText(String documentId, String pageId, String text) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_PDF_TEXT, false, text);
    }

    /** Word/PPT/Excel text extracted on import. */
    public void indexOfficeText(String documentId, String pageId, String text) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_OFFICE_TEXT, false, text);
    }

    /** OCR finished; lowConfidence marks garbage output so it ranks lower (spec 22.2). */
    public void indexOcr(String documentId, String pageId, String text, boolean lowConfidence) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_OCR, lowConfidence, text);
    }

    /** Handwriting recognition finished. */
    public void indexHandwriting(String documentId, String pageId, String text) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_HANDWRITING, false, text);
    }

    /** Audio transcript finished. */
    public void indexTranscript(String documentId, String pageId, String text) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_TRANSCRIPT, false, text);
    }

    /** Document created/renamed; pageId is "" for document-level segments. */
    public void indexFileName(String documentId, String title) {
        schedule(documentId, "", SearchResultMerger.SOURCE_FILE_NAME, false, title);
    }

    /** Page title / bookmark changed. */
    public void indexPageTitle(String documentId, String pageId, String title) {
        schedule(documentId, pageId, SearchResultMerger.SOURCE_PAGE_TITLE, false, title);
    }

    /** Tag attached to a document. */
    public void indexTag(String documentId, String tagName) {
        schedule(documentId, "", SearchResultMerger.SOURCE_TAG, false, tagName);
    }

    /** Drops every segment of a document (permanent delete or re-import). */
    public void removeDocument(String documentId) {
        AppExecutors.get().background().execute(() -> {
            try {
                indexDao.deleteSearchSegmentsForDocument(documentId);
            } catch (RuntimeException e) {
                AppLogger.w(TAG, "removeDocument failed", e);
            }
        });
    }

    private void schedule(String documentId, String pageId, String source,
                          boolean lowConfidence, String rawText) {
        final String docId = documentId == null ? "" : documentId;
        final String page = pageId == null ? "" : pageId;
        final String src = lowConfidence
                ? source + SearchResultMerger.LOW_CONFIDENCE_SUFFIX : source;
        AppExecutors.get().background().execute(() -> {
            try {
                String tokenized = tokenizeForIndex(rawText);
                // Delete both confidence variants so a confidence flip cannot
                // leave a stale row behind.
                indexDao.deleteSearchSegment(docId, page, source);
                indexDao.deleteSearchSegment(docId, page,
                        source + SearchResultMerger.LOW_CONFIDENCE_SUFFIX);
                if (tokenized.isEmpty()) {
                    // Clearing a text box clears its segment; no empty rows.
                    return;
                }
                SearchIndexEntity entity = new SearchIndexEntity(segmentRowId(docId, page, src));
                entity.documentId = docId;
                entity.pageId = page;
                entity.source = src;
                entity.content = tokenized;
                indexDao.replaceSearchSegment(entity);
            } catch (RuntimeException e) {
                AppLogger.w(TAG, "index segment failed: " + src, e);
            }
        });
    }

    /**
     * Normalizes then tokenizes so stored index terms exactly match the terms
     * {@link SearchQueryBuilder} produces for queries. Static + pure for tests.
     */
    static String tokenizeForIndex(String rawText) {
        List<String> tokens = NGramTokenizer.tokenize(TextNormalizer.normalize(rawText));
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Stable synthetic rowid for the FTS row. FTS4 tables have no real primary
     * key and Room would insert rowid=0 for every row otherwise; a 64-bit
     * FNV-1a hash of the segment key keeps inserts distinct and deterministic.
     */
    static long segmentRowId(String documentId, String pageId, String source) {
        String key = documentId + '|' + pageId + '|' + source;
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
