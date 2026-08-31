package com.leslie.colorosnote.feature.search;

import com.leslie.colorosnote.core.search.SearchDocument;
import com.leslie.colorosnote.core.search.SearchRanking;
import com.leslie.colorosnote.core.search.TextNormalizer;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges FTS hits with external matches (file name / tag), scores them via
 * {@link SearchRanking} (source weight, term coverage, low-confidence OCR
 * penalty) and builds UI-ready {@link SearchResultItem}s with snippets and
 * highlight ranges (spec 22.2/22.3). Pure JVM.
 */
public final class SearchResultMerger {

    /** Segment source strings stored in the FTS index by {@link SearchIndexScheduler}. */
    public static final String SOURCE_FILE_NAME = "file_name";
    public static final String SOURCE_FOLDER = "folder";
    public static final String SOURCE_TAG = "tag";
    public static final String SOURCE_TEXT_BOX = "text_box";
    public static final String SOURCE_PDF_TEXT = "pdf_text";
    public static final String SOURCE_OFFICE_TEXT = "office_text";
    public static final String SOURCE_OCR = "ocr";
    public static final String SOURCE_HANDWRITING = "handwriting";
    public static final String SOURCE_TRANSCRIPT = "transcript";
    public static final String SOURCE_PAGE_TITLE = "page_title";

    /**
     * Suffix appended to a source string to flag low-confidence content
     * (garbage OCR). The FTS table has no flag column, so the marker rides in
     * the source value; {@link #sourceTypeOf(String)} strips it and
     * {@link #isLowConfidence(String)} reports it.
     */
    public static final String LOW_CONFIDENCE_SUFFIX = ":lc";

    /** Snippet target length in characters. */
    static final int SNIPPET_LENGTH = 60;

    /** Supplies library metadata (title / folder path / updatedAt) per document. */
    public interface MetaProvider {
        /** May return null when the document is unknown. */
        DocMeta metaFor(String documentId);
    }

    /** Library metadata for one document. */
    public static final class DocMeta {
        public String title = "";
        public String folderPath = "";
        public long updatedAt;

        public DocMeta() {
        }

        public DocMeta(String title, String folderPath, long updatedAt) {
            this.title = title == null ? "" : title;
            this.folderPath = folderPath == null ? "" : folderPath;
            this.updatedAt = updatedAt;
        }
    }

    private SearchResultMerger() {
    }

    /**
     * Merges, dedupes (documentId+pageId+sourceType), scores and sorts.
     *
     * @param ftsMatches   rows from {@link IndexDao#search(String, int)}
     * @param extraMatches external matches (e.g. file name / tag lookups), may be null
     * @param queryTerms   terms from {@link SearchQueryBuilder#terms(String)}
     * @param metaProvider resolves titles/folder paths/update times, may be null
     * @param limit        max results; <= 0 means no limit
     */
    public static List<SearchResultItem> merge(List<IndexDao.SearchMatch> ftsMatches,
                                               List<SearchDocument> extraMatches,
                                               List<String> queryTerms,
                                               MetaProvider metaProvider,
                                               int limit) {
        List<String> terms = queryTerms == null ? new ArrayList<>() : new ArrayList<>(queryTerms);
        Map<String, Hit> dedup = new LinkedHashMap<>();
        if (ftsMatches != null) {
            for (IndexDao.SearchMatch m : ftsMatches) {
                if (m == null || m.documentId == null) {
                    continue;
                }
                Hit h = new Hit();
                h.documentId = m.documentId;
                h.pageId = m.pageId == null ? "" : m.pageId;
                h.type = sourceTypeOf(m.source);
                h.lowConfidence = isLowConfidence(m.source);
                h.text = detokenize(m.content);
                dedup.putIfAbsent(key(h), h);
            }
        }
        if (extraMatches != null) {
            for (SearchDocument d : extraMatches) {
                if (d == null || d.documentId == null) {
                    continue;
                }
                Hit h = new Hit();
                h.documentId = d.documentId;
                h.pageId = d.pageId == null ? "" : d.pageId;
                h.type = d.sourceType == null ? SearchDocument.SourceType.TEXT_BOX : d.sourceType;
                h.lowConfidence = d.lowConfidence;
                h.text = d.text == null ? "" : TextNormalizer.normalize(d.text);
                h.boundingBox = d.boundingBox;
                dedup.putIfAbsent(key(h), h);
            }
        }
        List<SearchResultItem> items = new ArrayList<>(dedup.size());
        for (Hit h : dedup.values()) {
            items.add(toItem(h, terms, metaProvider));
        }
        items.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) {
                return c;
            }
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        if (limit > 0 && items.size() > limit) {
            return new ArrayList<>(items.subList(0, limit));
        }
        return items;
    }

    /** Maps an FTS source string (with optional ":lc" suffix) to a SourceType. */
    static SearchDocument.SourceType sourceTypeOf(String source) {
        String s = source == null ? "" : source;
        if (isLowConfidence(s)) {
            s = s.substring(0, s.length() - LOW_CONFIDENCE_SUFFIX.length());
        }
        switch (s) {
            case SOURCE_FILE_NAME:
                return SearchDocument.SourceType.FILE_NAME;
            case SOURCE_FOLDER:
                return SearchDocument.SourceType.FOLDER;
            case SOURCE_TAG:
                return SearchDocument.SourceType.TAG;
            case SOURCE_TEXT_BOX:
                return SearchDocument.SourceType.TEXT_BOX;
            case SOURCE_PDF_TEXT:
                return SearchDocument.SourceType.PDF_TEXT;
            case SOURCE_OFFICE_TEXT:
                return SearchDocument.SourceType.OFFICE_TEXT;
            case SOURCE_OCR:
                return SearchDocument.SourceType.OCR;
            case SOURCE_HANDWRITING:
                return SearchDocument.SourceType.HANDWRITING;
            case SOURCE_TRANSCRIPT:
                return SearchDocument.SourceType.TRANSCRIPT;
            case SOURCE_PAGE_TITLE:
                return SearchDocument.SourceType.PAGE_TITLE;
            default:
                return SearchDocument.SourceType.TEXT_BOX;
        }
    }

    static boolean isLowConfidence(String source) {
        return source != null && source.endsWith(LOW_CONFIDENCE_SUFFIX);
    }

    /**
     * Rebuilds readable display text from the space-joined index tokens:
     * overlapping CJK bigrams merge back into the original run, latin words
     * keep their separating space.
     */
    static String detokenize(String tokenized) {
        if (tokenized == null || tokenized.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(tokenized.length());
        for (String part : tokenized.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() == 0) {
                sb.append(part);
                continue;
            }
            char last = sb.charAt(sb.length() - 1);
            if (TextNormalizer.isCjk(last) && TextNormalizer.isCjk(part.charAt(0))) {
                appendWithOverlap(sb, part);
            } else {
                sb.append(' ').append(part);
            }
        }
        return sb.toString();
    }

    /** Appends part dropping the longest overlap with the current suffix ("议纪" after "会议" adds "纪"). */
    private static void appendWithOverlap(StringBuilder sb, String part) {
        int max = Math.min(sb.length(), part.length());
        int k = max;
        while (k > 0 && !sb.substring(sb.length() - k).equals(part.substring(0, k))) {
            k--;
        }
        sb.append(part.substring(k));
    }

    static int countMatchedTerms(String text, List<String> terms) {
        int matched = 0;
        for (String t : terms) {
            if (!t.isEmpty() && text.contains(t)) {
                matched++;
            }
        }
        return matched;
    }

    private static SearchResultItem toItem(Hit h, List<String> terms, MetaProvider metaProvider) {
        SearchResultItem item = new SearchResultItem();
        item.documentId = h.documentId;
        item.pageId = h.pageId;
        item.sourceType = h.type;
        item.lowConfidence = h.lowConfidence;
        item.boundingBox = h.boundingBox;

        SearchDocument probe = new SearchDocument();
        probe.sourceType = h.type;
        probe.lowConfidence = h.lowConfidence;
        item.score = SearchRanking.score(probe, countMatchedTerms(h.text, terms), terms.size());

        Snippet snippet = buildSnippet(h.text, terms);
        item.snippet = snippet.text;
        item.highlightRanges = snippet.ranges;

        DocMeta meta = metaProvider == null ? null : metaProvider.metaFor(h.documentId);
        if (meta != null) {
            item.title = meta.title;
            item.folderPath = meta.folderPath;
            item.updatedAt = meta.updatedAt;
        }
        // A file-name hit with no metadata still has something to show.
        if (item.title.isEmpty() && h.type == SearchDocument.SourceType.FILE_NAME) {
            item.title = h.text;
        }
        return item;
    }

    /**
     * Cuts a ~60 char window around the first hit and records [start, end)
     * highlight ranges of every term occurrence inside the window.
     */
    static Snippet buildSnippet(String display, List<String> terms) {
        Snippet out = new Snippet();
        if (display == null || display.isEmpty()) {
            return out;
        }
        int first = Integer.MAX_VALUE;
        for (String t : terms) {
            if (t.isEmpty()) {
                continue;
            }
            int idx = display.indexOf(t);
            if (idx >= 0 && idx < first) {
                first = idx;
            }
        }
        int start = 0;
        if (first != Integer.MAX_VALUE && display.length() > SNIPPET_LENGTH) {
            start = Math.max(0, first - SNIPPET_LENGTH / 3);
            start = Math.min(start, display.length() - SNIPPET_LENGTH);
        }
        int end = Math.min(display.length(), start + SNIPPET_LENGTH);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append('…');
        }
        int bodyStart = sb.length();
        sb.append(display, start, end);
        if (end < display.length()) {
            sb.append('…');
        }
        out.text = sb.toString();
        for (String t : terms) {
            if (t.isEmpty()) {
                continue;
            }
            int idx = display.indexOf(t, start);
            while (idx >= 0 && idx < end) {
                int s = idx - start + bodyStart;
                out.ranges.add(new int[]{s, s + t.length()});
                idx = display.indexOf(t, idx + t.length());
            }
        }
        return out;
    }

    static final class Snippet {
        String text = "";
        final List<int[]> ranges = new ArrayList<>();
    }

    private static final class Hit {
        String documentId;
        String pageId = "";
        SearchDocument.SourceType type;
        boolean lowConfidence;
        String text = "";
        float[] boundingBox;
    }

    private static String key(Hit h) {
        return h.documentId + '|' + h.pageId + '|' + h.type.name();
    }
}
