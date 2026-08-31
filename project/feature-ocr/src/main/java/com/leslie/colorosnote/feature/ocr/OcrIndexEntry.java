package com.leslie.colorosnote.feature.ocr;

/**
 * One searchable OCR unit destined for core-search (spec 14.1: results must be
 * indexable and locatable on the original image). Pure JVM type; core-search
 * adapts this into its own SearchDocument.
 */
public final class OcrIndexEntry {

    public final String documentId;
    public final String pageId;
    public final String text;
    /** Hit region in original page-image coordinates. */
    public final OcrBox box;
    public final Float confidence; // nullable
    /** Low-confidence results rank lower in search instead of being trusted. */
    public final boolean lowConfidence;

    public OcrIndexEntry(String documentId, String pageId, String text, OcrBox box,
                         Float confidence, boolean lowConfidence) {
        this.documentId = documentId;
        this.pageId = pageId;
        this.text = text;
        this.box = box;
        this.confidence = confidence;
        this.lowConfidence = lowConfidence;
    }
}
