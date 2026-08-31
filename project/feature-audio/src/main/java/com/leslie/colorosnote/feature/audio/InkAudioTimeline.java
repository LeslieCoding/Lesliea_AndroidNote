package com.leslie.colorosnote.feature.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps ink strokes to recording timestamps and back (spec 20.1).
 * Pure JVM; persistence handled by a snapshot object.
 */
public final class InkAudioTimeline {

    /** Time range of one stroke relative to recording start. */
    public static final class StrokeTime {
        public String strokeId;
        public long startMs;
        public long endMs;

        public StrokeTime(String strokeId, long startMs, long endMs) {
            this.strokeId = strokeId;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private final Map<String, StrokeTime> byStrokeId = new HashMap<>();
    /** User calibration offset added to audio times (spec 20.1). */
    private long offsetMs = 0;

    public synchronized void attach(String strokeId, long startMs, long endMs) {
        if (strokeId == null) return;
        byStrokeId.put(strokeId, new StrokeTime(strokeId, startMs, endMs));
    }

    public synchronized void remove(String strokeId) {
        byStrokeId.remove(strokeId);
    }

    public synchronized void setOffsetMs(long offsetMs) {
        this.offsetMs = offsetMs;
    }

    public synchronized long getOffsetMs() {
        return offsetMs;
    }

    /** Audio time (with offset applied) for the middle of a stroke. Null if unknown. */
    public synchronized Long audioTimeForStroke(String strokeId) {
        StrokeTime t = byStrokeId.get(strokeId);
        if (t == null) return null;
        long mid = (t.startMs + t.endMs) / 2;
        return Math.max(0, mid + offsetMs);
    }

    /** Strokes written within +/- windowMs around the given audio time. */
    public synchronized List<StrokeTime> strokesNear(long audioTimeMs, long windowMs) {
        long t = audioTimeMs - offsetMs;
        List<StrokeTime> out = new ArrayList<>();
        for (StrokeTime st : byStrokeId.values()) {
            if (st.endMs >= t - windowMs && st.startMs <= t + windowMs) {
                out.add(st);
            }
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    /** All stroke times ordered by start (playback highlighting). */
    public synchronized List<StrokeTime> allOrdered() {
        List<StrokeTime> out = new ArrayList<>(byStrokeId.values());
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.offsetMs = offsetMs;
        s.entries = allOrdered();
        return s;
    }

    public synchronized void restore(Snapshot s) {
        byStrokeId.clear();
        offsetMs = s.offsetMs;
        if (s.entries != null) {
            for (StrokeTime t : s.entries) {
                byStrokeId.put(t.strokeId, t);
            }
        }
    }

    /** Serializable snapshot (Gson-friendly). */
    public static final class Snapshot {
        public long offsetMs;
        public List<StrokeTime> entries;
    }
}
