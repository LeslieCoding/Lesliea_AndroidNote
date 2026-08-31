package com.leslie.colorosnote.feature.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for the pure helpers of the index scheduler. */
public class SearchIndexSchedulerTest {

    @Test
    public void indexTokenizationMatchesQueryTokenization() {
        // Index terms must be identical to what SearchQueryBuilder emits.
        assertEquals("会议 议纪 纪要 会议纪要",
                SearchIndexScheduler.tokenizeForIndex("会议纪要"));
        assertEquals("abc", SearchIndexScheduler.tokenizeForIndex("ＡＢＣ"));
        assertEquals("q3 财报", SearchIndexScheduler.tokenizeForIndex("Q3财报"));
    }

    @Test
    public void emptyInputProducesNoIndexRow() {
        assertEquals("", SearchIndexScheduler.tokenizeForIndex(null));
        assertEquals("", SearchIndexScheduler.tokenizeForIndex(""));
        assertEquals("", SearchIndexScheduler.tokenizeForIndex("！！！"));
    }

    @Test
    public void segmentRowIdIsStableDistinctAndNonNegative() {
        long a = SearchIndexScheduler.segmentRowId("d1", "p1", "ocr");
        assertEquals(a, SearchIndexScheduler.segmentRowId("d1", "p1", "ocr"));
        assertNotEquals(a, SearchIndexScheduler.segmentRowId("d1", "p1", "text_box"));
        assertNotEquals(a, SearchIndexScheduler.segmentRowId("d1", "p2", "ocr"));
        assertNotEquals(a, SearchIndexScheduler.segmentRowId("d2", "p1", "ocr"));
        assertTrue(a >= 0);
    }
}
