package com.leslie.colorosnote.feature.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Recently-used sticker tracking (spec 18.3 最近使用): a capacity-bounded
 * LRU list, most recent first. Pure JVM; persistence (SharedPreferences) is
 * handled by the caller.
 */
public final class RecentStickers {

    public static final int DEFAULT_CAPACITY = 8;

    private final int capacity;
    private final List<String> ids = new ArrayList<>();

    public RecentStickers() {
        this(DEFAULT_CAPACITY);
    }

    public RecentStickers(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /** Marks a sticker as just used; moves it to the front. */
    public void record(String stickerId) {
        if (stickerId == null) {
            return;
        }
        ids.remove(stickerId);
        ids.add(0, stickerId);
        while (ids.size() > capacity) {
            ids.remove(ids.size() - 1);
        }
    }

    /** Most-recent-first snapshot. */
    public List<String> mostRecent() {
        return new ArrayList<>(ids);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** Replaces the list (persistence load); order is most-recent-first. */
    public void restore(List<String> stored) {
        ids.clear();
        if (stored == null) {
            return;
        }
        for (String id : stored) {
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        while (ids.size() > capacity) {
            ids.remove(ids.size() - 1);
        }
    }
}
