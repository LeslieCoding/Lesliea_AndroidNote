package com.leslie.colorosnote.core.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SpatialIndexTest {

    private static final SpatialIndex.Box WORLD =
            new SpatialIndex.Box(-10000, -10000, 10000, 10000);

    @Test
    public void insertAndQuery() {
        SpatialIndex idx = new SpatialIndex(WORLD);
        idx.insert("a", new SpatialIndex.Box(0, 0, 10, 10));
        idx.insert("b", new SpatialIndex.Box(100, 100, 110, 110));
        List<String> hit = idx.query(new SpatialIndex.Box(-5, -5, 20, 20));
        assertTrue(hit.contains("a"));
        assertFalse(hit.contains("b"));
    }

    @Test
    public void splitsUnderLoad() {
        SpatialIndex idx = new SpatialIndex(WORLD);
        for (int i = 0; i < 100; i++) {
            idx.insert("id" + i, new SpatialIndex.Box(i * 5, i * 5, i * 5 + 2, i * 5 + 2));
        }
        assertEquals(100, idx.size());
        List<String> hit = idx.query(new SpatialIndex.Box(0, 0, 50, 50));
        assertTrue(hit.size() >= 10);
    }

    @Test
    public void removeWorks() {
        SpatialIndex idx = new SpatialIndex(WORLD);
        SpatialIndex.Box box = new SpatialIndex.Box(0, 0, 10, 10);
        idx.insert("x", box);
        assertTrue(idx.remove("x", box));
        assertEquals(0, idx.size());
        assertTrue(idx.query(new SpatialIndex.Box(-1, -1, 20, 20)).isEmpty());
    }

    @Test
    public void straddlingBoxStillFound() {
        SpatialIndex idx = new SpatialIndex(WORLD);
        // crosses the world center -> stays on root after splits
        idx.insert("big", new SpatialIndex.Box(-50, -50, 50, 50));
        for (int i = 0; i < 20; i++) {
            idx.insert("s" + i, new SpatialIndex.Box(100 + i, 100 + i, 101 + i, 101 + i));
        }
        List<String> hit = idx.query(new SpatialIndex.Box(-10, -10, 10, 10));
        assertTrue(hit.contains("big"));
    }
}
