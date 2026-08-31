package com.leslie.colorosnote.document.pdf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe, size-bounded LRU cache with byte-size accounting.
 *
 * <p>Deliberately free of any Android dependencies so the eviction logic can be
 * unit-tested on the JVM. Values are weighed via {@link Weigher} (default: 1 byte
 * per entry); evicted values are reported to an optional {@link EvictionListener}
 * so callers can release native resources (e.g. recycle Bitmaps).
 *
 * @param <K> key type
 * @param <V> value type
 */
public class LruMemoryCache<K, V> {

    /** Computes the in-memory cost of one entry, in bytes. */
    public interface Weigher<K, V> {
        int weigh(K key, V value);
    }

    /** Invoked after a value leaves the cache (eviction, removal, clear or replace). */
    public interface EvictionListener<K, V> {
        void onEvicted(K key, V value);
    }

    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(0, 0.75f, true);
    private final Weigher<K, V> weigher;
    private final EvictionListener<K, V> evictionListener;

    private int maxSizeBytes;
    private int sizeBytes;

    /** Creates a cache where every entry weighs 1 byte and nothing listens for evictions. */
    public LruMemoryCache(int maxSizeBytes) {
        this(maxSizeBytes, null, null);
    }

    public LruMemoryCache(int maxSizeBytes, Weigher<K, V> weigher, EvictionListener<K, V> evictionListener) {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be > 0");
        }
        this.maxSizeBytes = maxSizeBytes;
        this.weigher = weigher;
        this.evictionListener = evictionListener;
    }

    public synchronized int maxSizeBytes() {
        return maxSizeBytes;
    }

    /** Adjusts the budget; trims immediately if the new budget is smaller. */
    public void setMaxSizeBytes(int maxSizeBytes) {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be > 0");
        }
        synchronized (this) {
            this.maxSizeBytes = maxSizeBytes;
        }
        trimToSize(maxSizeBytes);
    }

    public synchronized int sizeBytes() {
        return sizeBytes;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized boolean contains(K key) {
        return map.containsKey(key);
    }

    /** Returns the cached value (marking it most-recently-used), or null. */
    public synchronized V get(K key) {
        return map.get(key);
    }

    /**
     * Inserts or replaces a value and trims to the budget afterwards.
     * Returns the previous value, which has already been reported to the listener.
     */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key/value must not be null");
        }
        V previous;
        synchronized (this) {
            previous = map.put(key, value);
            sizeBytes += weigh(key, value);
            if (previous != null) {
                sizeBytes -= weigh(key, previous);
            }
        }
        if (previous != null) {
            notifyEvicted(key, previous);
        }
        trimToSize(maxSizeBytes);
        return previous;
    }

    /** Removes the entry, reporting it to the listener. Returns the removed value or null. */
    public V remove(K key) {
        V previous;
        synchronized (this) {
            previous = map.remove(key);
            if (previous == null) {
                return null;
            }
            sizeBytes -= weigh(key, previous);
        }
        notifyEvicted(key, previous);
        return previous;
    }

    /** Drops every entry, reporting each to the listener in LRU-first order. */
    public void clear() {
        trimToSize(0);
    }

    /** Evicts least-recently-used entries until the cache fits {@code maxBytes}. */
    public void trimToSize(int maxBytes) {
        while (true) {
            Map.Entry<K, V> eldest;
            synchronized (this) {
                if (sizeBytes <= maxBytes || map.isEmpty()) {
                    return;
                }
                eldest = map.entrySet().iterator().next();
                map.remove(eldest.getKey());
                sizeBytes -= weigh(eldest.getKey(), eldest.getValue());
            }
            // Listener runs outside the lock so callbacks may re-enter the cache.
            notifyEvicted(eldest.getKey(), eldest.getValue());
        }
    }

    private int weigh(K key, V value) {
        int w = weigher == null ? 1 : weigher.weigh(key, value);
        return Math.max(w, 0);
    }

    private void notifyEvicted(K key, V value) {
        if (evictionListener != null) {
            evictionListener.onEvicted(key, value);
        }
    }
}
