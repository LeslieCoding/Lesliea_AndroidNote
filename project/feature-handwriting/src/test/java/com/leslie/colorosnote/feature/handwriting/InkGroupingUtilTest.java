package com.leslie.colorosnote.feature.handwriting;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Unit tests for the pure geometry of {@link InkGroupingUtil}. */
public class InkGroupingUtilTest {

    private static final float EPS = 1e-4f;

    /** Two-point stroke from (x0,y0) to (x1,y1) over [t0,t1]. */
    private static StrokeData stroke(String id, float x0, float y0, float x1, float y1,
                                     long t0, long t1) {
        return new StrokeData(id, new float[]{x0, x1}, new float[]{y0, y1}, new long[]{t0, t1});
    }

    @Test
    public void sameLineStrokesMergeIntoOneLine() {
        // Two strokes side by side on the same baseline, written back to back.
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 110, 100, 200, 120, 200, 300);
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b));
        assertEquals(1, lines.size());
        assertEquals(InkGroup.Kind.LINE, lines.get(0).kind);
        assertEquals(Arrays.asList("a", "b"), lines.get(0).strokeIds);
    }

    @Test
    public void verticallySeparatedStrokesSplitIntoTwoLines() {
        // Median height ~20; vertical center distance 100 > 0.9 * 20.
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 0, 200, 100, 220, 200, 300);
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b));
        assertEquals(2, lines.size());
    }

    @Test
    public void longPauseSplitsLines() {
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 110, 100, 200, 120, 5000, 5100); // 4.9s pause
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b));
        assertEquals(2, lines.size());
    }

    @Test
    public void largeHorizontalGapSplitsLines() {
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 500, 100, 600, 120, 200, 300); // 400px gap
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b));
        assertEquals(2, lines.size());
    }

    @Test
    public void inputIsSortedByStartTime() {
        // Given out of order; groups must follow writing order.
        StrokeData later = stroke("b", 110, 100, 200, 120, 200, 300);
        StrokeData earlier = stroke("a", 0, 100, 100, 120, 0, 100);
        List<InkGroup> lines = InkGroupingUtil.groupIntoLines(Arrays.asList(later, earlier));
        assertEquals(1, lines.size());
        assertEquals(Arrays.asList("a", "b"), lines.get(0).strokeIds);
    }

    @Test
    public void groupRegionUnionsAllStrokes() {
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 110, 105, 200, 125, 200, 300);
        InkRect region = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b)).get(0).region;
        assertEquals(0f, region.left, EPS);
        assertEquals(100f, region.top, EPS);
        assertEquals(200f, region.right, EPS);
        assertEquals(125f, region.bottom, EPS);
    }

    @Test
    public void groupTimeSpanCoversAllStrokes() {
        StrokeData a = stroke("a", 0, 100, 100, 120, 10, 100);
        StrokeData b = stroke("b", 110, 100, 200, 120, 200, 350);
        InkGroup line = InkGroupingUtil.groupIntoLines(Arrays.asList(a, b)).get(0);
        assertEquals(10, line.startTime);
        assertEquals(350, line.endTime);
    }

    @Test
    public void blocksMergeNearbyLinesButSplitParagraphs() {
        // Line 1 and 2 are 30px apart (split into 2 lines: 30 > 0.9*20=18),
        // but close enough for one block (30 <= 2.2*20=44).
        StrokeData a = stroke("a", 0, 100, 100, 120, 0, 100);
        StrokeData b = stroke("b", 0, 130, 100, 150, 200, 300);
        // Paragraph break: 200px below and a long pause.
        StrokeData c = stroke("c", 0, 400, 100, 420, 10000, 10100);
        List<StrokeData> strokes = Arrays.asList(a, b, c);
        assertEquals(3, InkGroupingUtil.groupIntoLines(strokes).size());
        List<InkGroup> blocks = InkGroupingUtil.groupIntoBlocks(strokes);
        assertEquals(2, blocks.size());
        assertEquals(InkGroup.Kind.BLOCK, blocks.get(0).kind);
        assertEquals(Arrays.asList("a", "b"), blocks.get(0).strokeIds);
        assertEquals(Arrays.asList("c"), blocks.get(1).strokeIds);
    }

    @Test
    public void emptyInputYieldsNoGroups() {
        assertTrue(InkGroupingUtil.groupIntoLines(java.util.Collections.emptyList()).isEmpty());
        assertTrue(InkGroupingUtil.groupIntoBlocks(null).isEmpty());
    }

    @Test
    public void singleStrokeFormsSingleLineAndBlock() {
        StrokeData a = stroke("a", 0, 0, 50, 20, 0, 100);
        assertEquals(1, InkGroupingUtil.groupIntoLines(Arrays.asList(a)).size());
        assertEquals(1, InkGroupingUtil.groupIntoBlocks(Arrays.asList(a)).size());
    }

    @Test
    public void strokeDataRejectsInconsistentArrays() {
        try {
            new StrokeData("x", new float[]{1}, new float[]{1, 2}, new long[]{1});
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("mismatched point arrays must be rejected");
    }
}
