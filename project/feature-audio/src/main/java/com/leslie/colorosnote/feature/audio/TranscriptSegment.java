package com.leslie.colorosnote.feature.audio;

/**
 * One transcript segment (spec 21.3).
 */
public final class TranscriptSegment {
    public String id;
    public String text;
    public long startMs;
    public long endMs;
    /** Nullable; engine-dependent. */
    public Float confidence;
    /** Reserved for future speaker diarization. */
    public String speaker;

    public TranscriptSegment() {
    }

    public TranscriptSegment(String id, String text, long startMs, long endMs) {
        this.id = id;
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
    }
}
