package com.leslie.colorosnote.feature.handwriting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of strokes treated as one recognition unit (a line or a block).
 * Region and time span are precomputed for indexing. Pure JVM type.
 */
public final class InkGroup {

    public enum Kind {LINE, BLOCK}

    public final Kind kind;
    public final List<StrokeData> strokes;
    public final List<String> strokeIds;
    public final InkRect region;
    public final long startTime;
    public final long endTime;

    InkGroup(Kind kind, List<StrokeData> strokes) {
        this.kind = kind;
        this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
        List<String> ids = new ArrayList<>(strokes.size());
        InkRect region = null;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (StrokeData s : strokes) {
            ids.add(s.id);
            region = region == null ? s.bounds() : region.union(s.bounds());
            start = Math.min(start, s.startTime());
            end = Math.max(end, s.endTime());
        }
        this.strokeIds = Collections.unmodifiableList(ids);
        this.region = region;
        this.startTime = strokes.isEmpty() ? 0 : start;
        this.endTime = strokes.isEmpty() ? 0 : end;
    }
}
