package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the width-tier quantization and key identity. */
public class BackgroundCacheKeyTest {

    @Test
    public void quantizeWidthRoundsUpToTier() {
        assertEquals(256, BackgroundCacheKey.quantizeWidth(1));
        assertEquals(256, BackgroundCacheKey.quantizeWidth(255));
        assertEquals(256, BackgroundCacheKey.quantizeWidth(256));
        assertEquals(512, BackgroundCacheKey.quantizeWidth(257));
        assertEquals(512, BackgroundCacheKey.quantizeWidth(511));
        assertEquals(512, BackgroundCacheKey.quantizeWidth(512));
        assertEquals(1024, BackgroundCacheKey.quantizeWidth(1000));
    }

    @Test
    public void quantizeWidthClampsNonPositiveToOneTier() {
        assertEquals(256, BackgroundCacheKey.quantizeWidth(0));
        assertEquals(256, BackgroundCacheKey.quantizeWidth(-120));
    }

    @Test
    public void equalKeysHaveEqualHashCodes() {
        BackgroundCacheKey a = new BackgroundCacheKey("doc-1", 2, 512);
        BackgroundCacheKey b = new BackgroundCacheKey("doc-1", 2, 512);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void keysDifferOnEveryComponent() {
        BackgroundCacheKey base = new BackgroundCacheKey("doc-1", 2, 512);
        assertNotEquals(base, new BackgroundCacheKey("doc-2", 2, 512));
        assertNotEquals(base, new BackgroundCacheKey("doc-1", 3, 512));
        assertNotEquals(base, new BackgroundCacheKey("doc-1", 2, 1024));
        assertNotEquals(base, "doc-1#2@512px");
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullDocumentId() {
        new BackgroundCacheKey(null, 0, 256);
    }

    @Test
    public void toStringContainsAllComponents() {
        String s = new BackgroundCacheKey("doc-1", 2, 512).toString();
        assertTrue(s.contains("doc-1"));
        assertTrue(s.contains("2"));
        assertTrue(s.contains("512"));
    }
}
