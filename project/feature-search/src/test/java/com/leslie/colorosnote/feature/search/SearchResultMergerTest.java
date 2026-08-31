package com.leslie.colorosnote.feature.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.search.SearchDocument;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JVM tests for merge / ranking / snippet logic (spec 22.2/22.3). */
public class SearchResultMergerTest {

    private static IndexDao.SearchMatch match(String doc, String page, String source, String content) {
        IndexDao.SearchMatch m = new IndexDao.SearchMatch();
        m.documentId = doc;
        m.pageId = page;
        m.source = source;
        m.content = content;
        return m;
    }

    private static SearchResultMerger.MetaProvider metaOf(String title, String path, long updatedAt) {
        SearchResultMerger.DocMeta meta = new SearchResultMerger.DocMeta(title, path, updatedAt);
        return documentId -> meta;
    }

    @Test
    public void sourceMappingHandlesLowConfidenceSuffix() {
        assertEquals(SearchDocument.SourceType.OCR,
                SearchResultMerger.sourceTypeOf("ocr:lc"));
        assertTrue(SearchResultMerger.isLowConfidence("ocr:lc"));
        assertEquals(SearchDocument.SourceType.PDF_TEXT,
                SearchResultMerger.sourceTypeOf("pdf_text"));
        assertFalse(SearchResultMerger.isLowConfidence("pdf_text"));
        assertEquals(SearchDocument.SourceType.TEXT_BOX,
                SearchResultMerger.sourceTypeOf("unknown_source"));
        assertEquals(SearchDocument.SourceType.TEXT_BOX,
                SearchResultMerger.sourceTypeOf(null));
    }

    @Test
    public void detokenizeMergesBigramsBackIntoReadableText() {
        assertEquals("会议纪要", SearchResultMerger.detokenize("会议 议纪 纪要 会议纪要"));
        assertEquals("今天讨论了 q3", SearchResultMerger.detokenize("今天 讨论 论了 q3"));
        assertEquals("abc 123", SearchResultMerger.detokenize("abc 123"));
        assertEquals("", SearchResultMerger.detokenize(null));
        assertEquals("", SearchResultMerger.detokenize(""));
    }

    @Test
    public void fileNameOutranksOcrAtSameCoverage() {
        List<IndexDao.SearchMatch> matches = Arrays.asList(
                match("docOcr", "p1", "ocr", "会议 议纪 纪要"),
                match("docFile", "", "file_name", "会议 议纪 纪要"));
        List<String> terms = SearchQueryBuilder.terms("会议纪要");
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, terms, metaOf("标题", "工作", 1000L), 0);
        assertEquals(2, items.size());
        assertEquals("docFile", items.get(0).documentId);
        assertEquals(SearchDocument.SourceType.FILE_NAME, items.get(0).sourceType);
        assertEquals("docOcr", items.get(1).documentId);
    }

    @Test
    public void lowConfidenceOcrIsDownWeighted() {
        List<IndexDao.SearchMatch> matches = Arrays.asList(
                match("docBad", "p1", "ocr:lc", "会议 议纪 纪要"),
                match("docGood", "p1", "ocr", "会议 议纪 纪要"));
        List<String> terms = SearchQueryBuilder.terms("会议纪要");
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, terms, metaOf("t", "", 0L), 0);
        assertEquals("docGood", items.get(0).documentId);
        assertFalse(items.get(0).lowConfidence);
        assertTrue(items.get(1).lowConfidence);
        assertTrue(items.get(0).score > items.get(1).score);
    }

    @Test
    public void higherTermCoverageRanksHigher() {
        List<IndexDao.SearchMatch> matches = Arrays.asList(
                match("docPartial", "p1", "text_box", "会议"),
                match("docFull", "p1", "text_box", "会议 议纪 纪要 会议纪要"));
        List<String> terms = SearchQueryBuilder.terms("会议纪要");
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, terms, metaOf("t", "", 0L), 0);
        assertEquals("docFull", items.get(0).documentId);
    }

    @Test
    public void updatedAtBreaksScoreTies() {
        List<IndexDao.SearchMatch> matches = Arrays.asList(
                match("docOld", "p1", "text_box", "会议"),
                match("docNew", "p1", "text_box", "会议"));
        Map<String, SearchResultMerger.DocMeta> metas = new HashMap<>();
        metas.put("docOld", new SearchResultMerger.DocMeta("a", "", 100L));
        metas.put("docNew", new SearchResultMerger.DocMeta("b", "", 200L));
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, SearchQueryBuilder.terms("会议"), metas::get, 0);
        assertEquals("docNew", items.get(0).documentId);
    }

    @Test
    public void snippetIsWindowedAroundHitWithValidHighlightRanges() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("ab");
        }
        sb.append("关键");
        for (int i = 0; i < 5; i++) {
            sb.append("cd");
        }
        SearchResultMerger.Snippet snippet =
                SearchResultMerger.buildSnippet(sb.toString(), Arrays.asList("关键"));
        assertTrue(snippet.text.startsWith("…"));
        assertTrue(snippet.text.length() <= SearchResultMerger.SNIPPET_LENGTH + 2);
        assertEquals(1, snippet.ranges.size());
        int[] r = snippet.ranges.get(0);
        assertEquals("关键", snippet.text.substring(r[0], r[1]));
    }

    @Test
    public void snippetWithoutDirectHitShowsHead() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("xy");
        }
        SearchResultMerger.Snippet snippet =
                SearchResultMerger.buildSnippet(sb.toString(), Arrays.asList("zzz"));
        assertFalse(snippet.text.startsWith("…"));
        assertTrue(snippet.text.endsWith("…"));
        assertTrue(snippet.ranges.isEmpty());
    }

    @Test
    public void duplicatesCollapseAndLimitTrims() {
        List<IndexDao.SearchMatch> matches = new ArrayList<>(Arrays.asList(
                match("d1", "p1", "text_box", "会议"),
                match("d1", "p1", "text_box", "会议"),
                match("d2", "p1", "text_box", "会议"),
                match("d3", "p1", "text_box", "会议")));
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, SearchQueryBuilder.terms("会议"), metaOf("t", "", 0L), 2);
        assertEquals(2, items.size());
    }

    @Test
    public void extraMatchesCarryBoundingBoxAndType() {
        SearchDocument tag = new SearchDocument();
        tag.documentId = "docTag";
        tag.sourceType = SearchDocument.SourceType.TAG;
        tag.text = "会议纪要";
        tag.boundingBox = new float[]{1f, 2f, 3f, 4f};
        List<SearchResultItem> items = SearchResultMerger.merge(
                new ArrayList<>(), Arrays.asList(tag),
                SearchQueryBuilder.terms("会议纪要"), metaOf("t", "", 5L), 0);
        assertEquals(1, items.size());
        assertEquals(SearchDocument.SourceType.TAG, items.get(0).sourceType);
        assertNotNull(items.get(0).boundingBox);
        assertEquals(3f, items.get(0).boundingBox[2], 0.001f);
    }

    @Test
    public void fileNameHitFallsBackToContentAsTitle() {
        List<IndexDao.SearchMatch> matches = Arrays.asList(
                match("docX", "", "file_name", "会议 议纪 纪要"));
        List<SearchResultItem> items = SearchResultMerger.merge(
                matches, null, SearchQueryBuilder.terms("会议纪要"), null, 0);
        assertEquals("会议纪要", items.get(0).title);
        assertNull(items.get(0).boundingBox);
        assertEquals("", items.get(0).folderPath);
    }
}
