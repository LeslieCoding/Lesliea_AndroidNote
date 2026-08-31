package com.leslie.colorosnote.feature.search;

import com.leslie.colorosnote.core.search.SearchDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * One UI-ready search result row (spec 22.3). Pure JVM value object produced
 * by {@link SearchResultMerger}.
 */
public final class SearchResultItem {

    public String documentId;
    /** Target page; "" when the hit is document-level (file name, tag, ...). */
    public String pageId = "";
    public SearchDocument.SourceType sourceType;
    public String title = "";
    /** Display snippet (~60 chars) centered on the first hit. */
    public String snippet = "";
    /** [start, end) char ranges inside {@link #snippet} to highlight. */
    public List<int[]> highlightRanges = new ArrayList<>();
    /** Optional hit region [left, top, right, bottom] in page coordinates. */
    public float[] boundingBox;
    /** Folder path for display, e.g. "工作/项目A"; "" = library root. */
    public String folderPath = "";
    public long updatedAt;
    /** Low-confidence source (garbage OCR), already down-weighted in score. */
    public boolean lowConfidence;
    public double score;
}
