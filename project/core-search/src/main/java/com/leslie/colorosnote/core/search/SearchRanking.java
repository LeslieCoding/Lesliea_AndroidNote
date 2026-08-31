package com.leslie.colorosnote.core.search;

/**
 * Relevance scoring for search results (spec 22.2): source-type weights and
 * low-confidence OCR down-weighting. Pure JVM, deterministic.
 */
public final class SearchRanking {

    private SearchRanking() {
    }

    /** Base weight per source type; higher = more trustworthy/precise. */
    public static double sourceWeight(SearchDocument.SourceType type) {
        switch (type) {
            case FILE_NAME:   return 5.0;
            case PAGE_TITLE:  return 4.0;
            case TAG:         return 3.5;
            case TEXT_BOX:    return 3.0;
            case PDF_TEXT:    return 2.5;
            case OFFICE_TEXT: return 2.5;
            case HANDWRITING: return 2.0;
            case TRANSCRIPT:  return 1.8;
            case OCR:         return 1.5;
            case FOLDER:      return 1.2;
            default:          return 1.0;
        }
    }

    /**
     * Score one hit: base source weight, term-coverage boost, low-confidence
     * penalty (spec: OCR garbage must not outrank good results).
     */
    public static double score(SearchDocument doc, int matchedTerms, int totalTerms) {
        double w = sourceWeight(doc.sourceType);
        if (totalTerms > 0) {
            w *= 0.5 + 0.5 * ((double) matchedTerms / totalTerms);
        }
        if (doc.lowConfidence) {
            w *= 0.3;
        }
        return w;
    }
}
