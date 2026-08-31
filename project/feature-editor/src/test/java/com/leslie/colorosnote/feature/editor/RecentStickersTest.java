package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Pure-JVM tests for recently-used sticker ordering (spec 18.3 最近使用). */
public class RecentStickersTest {

    @Test
    public void mostRecentFirst() {
        RecentStickers recent = new RecentStickers();
        recent.record("a");
        recent.record("b");
        recent.record("c");
        assertEquals(Arrays.asList("c", "b", "a"), recent.mostRecent());
    }

    @Test
    public void reRecordingMovesToFront() {
        RecentStickers recent = new RecentStickers();
        recent.record("a");
        recent.record("b");
        recent.record("c");
        recent.record("a");
        assertEquals(Arrays.asList("a", "c", "b"), recent.mostRecent());
    }

    @Test
    public void capacityDropsLeastRecent() {
        RecentStickers recent = new RecentStickers(3);
        recent.record("a");
        recent.record("b");
        recent.record("c");
        recent.record("d");
        assertEquals(Arrays.asList("d", "c", "b"), recent.mostRecent());
    }

    @Test
    public void restoreDedupesAndTrims() {
        RecentStickers recent = new RecentStickers(2);
        recent.restore(Arrays.asList("a", "b", "a", "c"));
        assertEquals(Arrays.asList("a", "b"), recent.mostRecent());
    }

    @Test
    public void nullIdsIgnored() {
        RecentStickers recent = new RecentStickers();
        recent.record(null);
        assertTrue(recent.isEmpty());
    }
}
