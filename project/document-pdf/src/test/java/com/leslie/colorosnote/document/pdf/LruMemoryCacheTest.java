package com.leslie.colorosnote.document.pdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Pure-JVM tests for the LRU eviction and byte-accounting logic. */
public class LruMemoryCacheTest {

    /** A value with a fixed byte weight. */
    private static final class Weighted {
        final int bytes;

        Weighted(int bytes) {
            this.bytes = bytes;
        }
    }

    private static LruMemoryCache.Weigher<String, Weighted> byField() {
        return (key, value) -> value.bytes;
    }

    @Test
    public void putAndGetReturnsValueAndAccumulatesBytes() {
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(), null);
        Weighted v = new Weighted(40);
        cache.put("a", v);
        assertSame(v, cache.get("a"));
        assertEquals(40, cache.sizeBytes());
        assertEquals(1, cache.size());
    }

    @Test
    public void evictsLeastRecentlyUsedWhenOverBudget() {
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(), null);
        cache.put("a", new Weighted(50));
        cache.put("b", new Weighted(50));
        cache.get("a"); // a becomes most-recently-used
        cache.put("c", new Weighted(50)); // must evict b, not a
        assertNull(cache.get("b"));
        assertTrue(cache.contains("a"));
        assertTrue(cache.contains("c"));
        assertEquals(100, cache.sizeBytes());
    }

    @Test
    public void entryLargerThanBudgetEvictsEverythingIncludingItself() {
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(), null);
        cache.put("a", new Weighted(60));
        cache.put("huge", new Weighted(150));
        assertEquals(0, cache.size());
        assertEquals(0, cache.sizeBytes());
    }

    @Test
    public void replacingKeyAdjustsSizeAndNotifiesOnce() {
        List<String> evicted = new ArrayList<>();
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(),
                (key, value) -> evicted.add(key + ":" + value.bytes));
        cache.put("a", new Weighted(30));
        cache.put("a", new Weighted(70));
        assertEquals(70, cache.sizeBytes());
        assertEquals(1, cache.size());
        assertEquals(1, evicted.size());
        assertEquals("a:30", evicted.get(0));
    }

    @Test
    public void evictionListenerReceivesLruFirstOrder() {
        List<String> evicted = new ArrayList<>();
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(),
                (key, value) -> evicted.add(key));
        cache.put("a", new Weighted(50));
        cache.put("b", new Weighted(50));
        cache.put("c", new Weighted(80)); // 180 total: evicts a then b to fit 100
        assertEquals(2, evicted.size());
        assertEquals("a", evicted.get(0));
        assertEquals("b", evicted.get(1));
        assertEquals(80, cache.sizeBytes());
    }

    @Test
    public void removeNotifiesListenerAndShrinks() {
        List<String> evicted = new ArrayList<>();
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(),
                (key, value) -> evicted.add(key));
        cache.put("a", new Weighted(50));
        Weighted removed = cache.remove("a");
        assertEquals(50, removed.bytes);
        assertEquals(0, cache.sizeBytes());
        assertEquals(1, evicted.size());
        assertNull(cache.remove("missing"));
    }

    @Test
    public void clearEvictsEverything() {
        List<String> evicted = new ArrayList<>();
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(),
                (key, value) -> evicted.add(key));
        cache.put("a", new Weighted(30));
        cache.put("b", new Weighted(30));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.sizeBytes());
        assertEquals(2, evicted.size());
        assertFalse(cache.contains("a"));
    }

    @Test
    public void shrinkingBudgetTrimsImmediately() {
        LruMemoryCache<String, Weighted> cache = new LruMemoryCache<>(100, byField(), null);
        cache.put("a", new Weighted(60));
        cache.put("b", new Weighted(30));
        cache.setMaxSizeBytes(50);
        assertEquals(30, cache.sizeBytes());
        assertFalse(cache.contains("a"));
        assertTrue(cache.contains("b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveBudget() {
        new LruMemoryCache<String, Weighted>(0);
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullValue() {
        new LruMemoryCache<String, Weighted>(10).put("a", null);
    }

    @Test
    public void defaultWeigherCountsOnePerEntry() {
        LruMemoryCache<String, String> cache = new LruMemoryCache<>(2);
        cache.put("a", "x");
        cache.put("b", "y");
        cache.put("c", "z"); // evicts "a"
        assertEquals(2, cache.size());
        assertFalse(cache.contains("a"));
    }
}
