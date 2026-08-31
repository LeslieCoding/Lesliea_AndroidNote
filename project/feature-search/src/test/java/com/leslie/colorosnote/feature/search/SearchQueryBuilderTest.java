package com.leslie.colorosnote.feature.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** JVM tests for FTS4 query construction (spec 22.2). */
public class SearchQueryBuilderTest {

    @Test
    public void blankInputProducesEmptyQuery() {
        assertEquals("", SearchQueryBuilder.buildFtsQuery(null));
        assertEquals("", SearchQueryBuilder.buildFtsQuery(""));
        assertEquals("", SearchQueryBuilder.buildFtsQuery("   "));
        // Fullwidth punctuation normalizes away and yields no terms.
        assertEquals("", SearchQueryBuilder.buildFtsQuery("！！！"));
        assertTrue(SearchQueryBuilder.isBlank(null));
        assertTrue(SearchQueryBuilder.isBlank("  "));
        assertFalse(SearchQueryBuilder.isBlank("会议"));
    }

    @Test
    public void chineseRunBecomesAndedBigrams() {
        // "会议纪要" -> overlapping bigrams + whole run, AND semantics via spaces.
        assertEquals("\"会议\" \"议纪\" \"纪要\" \"会议纪要\"",
                SearchQueryBuilder.buildFtsQuery("会议纪要"));
    }

    @Test
    public void twoCharChineseKeepsSingleToken() {
        assertEquals("\"笔记\"", SearchQueryBuilder.buildFtsQuery("笔记"));
    }

    @Test
    public void latinWordsGetPrefixStar() {
        assertEquals("\"hello\"* \"world\"*", SearchQueryBuilder.buildFtsQuery("Hello World"));
    }

    @Test
    public void fullwidthLatinIsNormalizedBeforeQuerying() {
        assertEquals("\"abc\"*", SearchQueryBuilder.buildFtsQuery("ＡＢＣ"));
    }

    @Test
    public void mixedInputSplitsScripts() {
        assertEquals(Arrays.asList("q3", "财报"), SearchQueryBuilder.terms("Q3财报"));
        assertEquals("\"q3\"* \"财报\"", SearchQueryBuilder.buildFtsQuery("Q3财报"));
    }

    @Test
    public void quotesAndSpecialCharsCannotBreakTheQuery() {
        List<String> terms = SearchQueryBuilder.terms("说\"话");
        assertEquals(Arrays.asList("说", "话"), terms);
        String q = SearchQueryBuilder.buildFtsQuery("说\"话");
        assertEquals("\"说\" \"话\"", q);
    }
}
