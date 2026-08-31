package com.leslie.colorosnote.feature.handwriting;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link HandwritingSearchIndexer} and entry JSON round-trips. */
public class HandwritingSearchIndexerTest {

    private static final float EPS = 1e-4f;

    private static StrokeData stroke(String id, float x0, float y0, float x1, float y1,
                                     long t0, long t1) {
        return new StrokeData(id, new float[]{x0, x1}, new float[]{y0, y1}, new long[]{t0, t1});
    }

    private static InkGroup lineOf(StrokeData... strokes) {
        return InkGroupingUtil.groupIntoLines(Arrays.asList(strokes)).get(0);
    }

    private final HandwritingSearchIndexer indexer = new HandwritingSearchIndexer();

    @Test
    public void buildEntries_usesBestCandidateAndGroupMetadata() {
        InkGroup line = lineOf(
                stroke("a", 0, 100, 100, 120, 10, 100),
                stroke("b", 110, 100, 200, 120, 200, 300));
        List<Candidate> candidates = Arrays.asList(
                new Candidate("你好", 0.9f), new Candidate("你奸", 0.3f));
        List<HandwritingIndexEntry> entries = indexer.buildEntries("doc1", "page1",
                Collections.singletonList(new HandwritingSearchIndexer.RecognizedGroup(line, candidates)));

        assertEquals(1, entries.size());
        HandwritingIndexEntry e = entries.get(0);
        assertEquals("doc1", e.documentId);
        assertEquals("page1", e.pageId);
        assertEquals("你好", e.text);
        assertEquals(Arrays.asList("a", "b"), e.strokeIds);
        assertEquals(10, e.timestamp);
        assertFalse(e.lowConfidence);
        // Region links back to the page coordinates for jump-and-highlight.
        assertEquals(0f, e.region[0], EPS);
        assertEquals(100f, e.region[1], EPS);
        assertEquals(200f, e.region[2], EPS);
        assertEquals(120f, e.region[3], EPS);
    }

    @Test
    public void buildEntries_lowScoreIsFlagged() {
        InkGroup line = lineOf(stroke("a", 0, 0, 100, 20, 0, 100));
        List<HandwritingIndexEntry> entries = indexer.buildEntries("d", "p",
                Collections.singletonList(new HandwritingSearchIndexer.RecognizedGroup(
                        line, Collections.singletonList(new Candidate("难认", 0.2f)))));
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).lowConfidence);
    }

    @Test
    public void buildEntries_skipsBlankAndMissingCandidates() {
        InkGroup line = lineOf(stroke("a", 0, 0, 100, 20, 0, 100));
        List<HandwritingIndexEntry> entries = indexer.buildEntries("d", "p", Arrays.asList(
                new HandwritingSearchIndexer.RecognizedGroup(line, Collections.emptyList()),
                new HandwritingSearchIndexer.RecognizedGroup(
                        line, Collections.singletonList(new Candidate("   ", 0.9f))),
                new HandwritingSearchIndexer.RecognizedGroup(
                        line, Collections.singletonList(new Candidate("有效", 0.9f)))));
        assertEquals(1, entries.size());
        assertEquals("有效", entries.get(0).text);
    }

    @Test
    public void buildEntries_nullAndEmptyGroupsAreSafe() {
        assertTrue(indexer.buildEntries("d", "p", null).isEmpty());
        assertTrue(indexer.buildEntries("d", "p", Collections.emptyList()).isEmpty());
    }

    @Test
    public void singleEntryJsonRoundTrip() {
        HandwritingIndexEntry e = new HandwritingIndexEntry("doc", "page", "手写文字",
                new float[]{1, 2, 3, 4}, Arrays.asList("s1", "s2"), 12345L, true);
        HandwritingIndexEntry back = HandwritingIndexEntry.fromJson(e.toJson());
        assertEquals(e.documentId, back.documentId);
        assertEquals(e.pageId, back.pageId);
        assertEquals(e.text, back.text);
        assertEquals(4, back.region.length);
        assertEquals(3f, back.region[2], EPS);
        assertEquals(Arrays.asList("s1", "s2"), back.strokeIds);
        assertEquals(12345L, back.timestamp);
        assertTrue(back.lowConfidence);
    }

    @Test
    public void entryListJsonRoundTrip() {
        InkGroup line = lineOf(stroke("a", 0, 0, 100, 20, 0, 100));
        List<HandwritingIndexEntry> entries = indexer.buildEntries("doc", "page",
                Collections.singletonList(new HandwritingSearchIndexer.RecognizedGroup(
                        line, Collections.singletonList(new Candidate("测试", 0.8f)))));
        List<HandwritingIndexEntry> back = indexer.entriesFromJson(indexer.entriesToJson(entries));
        assertEquals(1, back.size());
        assertEquals("测试", back.get(0).text);
        assertEquals("doc", back.get(0).documentId);
        assertEquals(Arrays.asList("a"), back.get(0).strokeIds);
        assertFalse(back.get(0).lowConfidence);
    }

    @Test
    public void entriesFromJson_nullYieldsEmptyList() {
        assertTrue(indexer.entriesFromJson("null").isEmpty());
    }
}
