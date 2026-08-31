package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for the provider caching policy: page bounds, width tiers,
 * LRU eviction, failed-render retry and release semantics. String values stand
 * in for bitmaps so no Android classes are touched.
 */
public class BackgroundRenderCacheTest {

    /** Counting renderer producing fixed-weight strings. */
    private static final class FakeRenderer implements BackgroundRenderCache.Renderer<String> {
        final AtomicInteger calls = new AtomicInteger();
        final int weight;
        boolean fail;

        FakeRenderer(int weight) {
            this.weight = weight;
        }

        @Override
        public String render(BackgroundCacheKey key) {
            calls.incrementAndGet();
            if (fail) {
                return null;
            }
            StringBuilder sb = new StringBuilder("p" + key.pageIndex + "@" + key.widthPx);
            while (sb.length() < weight) {
                sb.append('.');
            }
            return sb.toString();
        }
    }

    private static BackgroundRenderCache<String> newCache(String docId, int pageCount,
                                                          FakeRenderer renderer,
                                                          int cacheBytes,
                                                          List<BackgroundCacheKey> evicted) {
        return new BackgroundRenderCache<>(docId, () -> pageCount, renderer, cacheBytes,
                (key, value) -> value.length(),
                (key, value) -> {
                    if (evicted != null) {
                        evicted.add(key);
                    }
                });
    }

    @Test
    public void rendersOnceAndServesFromCache() {
        FakeRenderer renderer = new FakeRenderer(8);
        BackgroundRenderCache<String> cache = newCache("doc", 5, renderer, 1024, null);
        String first = cache.get(1, 500);
        assertSame(first, cache.get(1, 500));
        assertEquals(1, renderer.calls.get());
        assertEquals("p1@512..", first);
    }

    @Test
    public void widthTiersAreSeparateCacheEntries() {
        FakeRenderer renderer = new FakeRenderer(8);
        BackgroundRenderCache<String> cache = newCache("doc", 5, renderer, 1024, null);
        String small = cache.get(0, 200); // tier 256
        String large = cache.get(0, 300); // tier 512
        assertEquals("p0@256..", small);
        assertEquals("p0@512..", large);
        assertEquals(2, renderer.calls.get());
    }

    @Test
    public void outOfRangePagesReturnNullWithoutRendering() {
        FakeRenderer renderer = new FakeRenderer(8);
        BackgroundRenderCache<String> cache = newCache("doc", 3, renderer, 1024, null);
        assertNull(cache.get(-1, 256));
        assertNull(cache.get(3, 256));
        assertNull(cache.get(100, 256));
        assertEquals(0, renderer.calls.get());
    }

    @Test
    public void failedRendersAreNotCachedAndRetried() {
        FakeRenderer renderer = new FakeRenderer(8);
        renderer.fail = true;
        BackgroundRenderCache<String> cache = newCache("doc", 3, renderer, 1024, null);
        assertNull(cache.get(0, 256));
        assertEquals(1, renderer.calls.get());
        renderer.fail = false;
        assertEquals("p0@256..", cache.get(0, 256));
        assertEquals(2, renderer.calls.get());
    }

    @Test
    public void evictsLeastRecentlyUsedPageOverBudget() {
        FakeRenderer renderer = new FakeRenderer(10);
        List<BackgroundCacheKey> evicted = new ArrayList<>();
        BackgroundRenderCache<String> cache = newCache("doc", 9, renderer, 20, evicted);
        cache.get(0, 256); // 10 bytes
        cache.get(1, 256); // 20 bytes total
        cache.get(0, 256); // page 0 becomes most-recently-used
        cache.get(2, 256); // 30 > 20: evicts page 1, not page 0
        assertEquals(1, evicted.size());
        assertEquals(new BackgroundCacheKey("doc", 1, 256), evicted.get(0));
        // Page 1 must be re-rendered on next access; page 0 stays cached.
        int callsBefore = renderer.calls.get();
        assertEquals("p0@256....", cache.get(0, 256));
        assertEquals(callsBefore, renderer.calls.get());
        cache.get(1, 256);
        assertEquals(callsBefore + 1, renderer.calls.get());
    }

    @Test
    public void removeDropsEntryAndReportsEviction() {
        FakeRenderer renderer = new FakeRenderer(10);
        List<BackgroundCacheKey> evicted = new ArrayList<>();
        BackgroundRenderCache<String> cache = newCache("doc", 3, renderer, 1024, evicted);
        cache.get(0, 256);
        String removed = cache.remove(0, 256);
        assertEquals("p0@256....", removed);
        assertEquals(1, evicted.size());
        cache.get(0, 256); // re-renders
        assertEquals(2, renderer.calls.get());
        assertNull(cache.remove(0, 999)); // different tier: nothing to remove
    }

    @Test
    public void releaseClearsCacheAndBlocksFurtherGets() {
        FakeRenderer renderer = new FakeRenderer(10);
        List<BackgroundCacheKey> evicted = new ArrayList<>();
        BackgroundRenderCache<String> cache = newCache("doc", 3, renderer, 1024, evicted);
        cache.get(0, 256);
        cache.get(1, 256);
        cache.release();
        assertTrue(cache.isReleased());
        assertEquals(2, evicted.size());
        assertNull(cache.get(0, 256));
        assertEquals(2, renderer.calls.get()); // no render after release
        cache.release(); // idempotent: no extra evictions
        assertEquals(2, evicted.size());
    }

    @Test
    public void defaultBudgetIsOneEighthOfHeapWithFloor() {
        int budget = PageBackgroundProvider.defaultCacheBytes();
        long maxHeap = Runtime.getRuntime().maxMemory();
        long expected = Math.max(8L * 1024 * 1024, maxHeap / 8);
        assertEquals((int) Math.min(expected, Integer.MAX_VALUE), budget);
        assertTrue(budget >= 8 * 1024 * 1024);
    }
}
