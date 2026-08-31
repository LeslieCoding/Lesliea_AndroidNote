package com.leslie.colorosnote.core.search;

/**
 * Source categories for indexed content (spec 22). Each entry links a piece of
 * text back to a document/page/region so results can jump and highlight.
 */
public final class SearchDocument {

    public enum SourceType {
        FILE_NAME,
        FOLDER,
        TAG,
        TEXT_BOX,
        PDF_TEXT,
        OFFICE_TEXT,
        OCR,
        HANDWRITING,
        TRANSCRIPT,
        PAGE_TITLE
    }

    public String documentId;
    public String pageId;      // nullable for doc-level entries
    public SourceType sourceType;
    public String text;          // raw
    public String normalizedText; // TextNormalizer output
    public String language;      // nullable
    /** Optional hit region in page coordinates: [left, top, right, bottom]. */
    public float[] boundingBox;  // nullable
    public long timestamp;
    public long updatedAt;
    /** Low confidence sources (bad OCR) rank lower (spec 22.2). */
    public boolean lowConfidence;

    public SearchDocument() {
    }
}
