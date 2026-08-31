package com.leslie.colorosnote.feature.audio;

/**
 * Segments long recordings into rolling files so a crash only loses the
 * current segment (spec 20: 录音文件分段防损坏). Pure JVM decision logic;
 * the recorder shell feeds it timestamps.
 */
public final class SegmentRollingPolicy {

    /** Default roll every 5 minutes. */
    public static final long DEFAULT_SEGMENT_MS = 5 * 60 * 1000;

    private final long segmentMs;
    private long currentSegmentStart = -1;
    private int segmentIndex = -1;

    public SegmentRollingPolicy(long segmentMs) {
        this.segmentMs = segmentMs <= 0 ? DEFAULT_SEGMENT_MS : segmentMs;
    }

    /**
     * Called with elapsed recording time. Returns true when a new segment
     * file must be started (including the very first call).
     */
    public synchronized boolean onTick(long elapsedMs) {
        if (currentSegmentStart < 0 || elapsedMs - currentSegmentStart >= segmentMs) {
            currentSegmentStart = elapsedMs;
            segmentIndex++;
            return true;
        }
        return false;
    }

    public synchronized int currentSegmentIndex() {
        return segmentIndex;
    }

    /** Deterministic segment file name. */
    public static String segmentFileName(String baseName, int index) {
        return String.format(java.util.Locale.ROOT, "%s_part%03d.m4a", baseName, index);
    }
}
