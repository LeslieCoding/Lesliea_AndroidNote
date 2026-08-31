package com.leslie.colorosnote.feature.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory transcript segment store with TXT / Markdown export (spec 21.3).
 * Pure JVM; file persistence is the caller's job.
 */
public final class TranscriptStore {

    private final List<TranscriptSegment> segments = new ArrayList<>();

    public synchronized void add(TranscriptSegment s) {
        segments.add(s);
        segments.sort((a, b) -> Long.compare(a.startMs, b.startMs));
    }

    public synchronized boolean remove(String id) {
        return segments.removeIf(s -> s.id != null && s.id.equals(id));
    }

    public synchronized boolean updateText(String id, String newText) {
        for (TranscriptSegment s : segments) {
            if (s.id != null && s.id.equals(id)) {
                s.text = newText;
                return true;
            }
        }
        return false;
    }

    public synchronized List<TranscriptSegment> all() {
        return new ArrayList<>(segments);
    }

    /** Segment covering a given audio time, or null. */
    public synchronized TranscriptSegment segmentAt(long audioTimeMs) {
        for (TranscriptSegment s : segments) {
            if (audioTimeMs >= s.startMs && audioTimeMs <= s.endMs) return s;
        }
        return null;
    }

    public synchronized String exportTxt() {
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment s : segments) {
            sb.append('[').append(formatTime(s.startMs)).append("] ")
                    .append(s.text == null ? "" : s.text).append('\n');
        }
        return sb.toString();
    }

    public synchronized String exportMarkdown() {
        StringBuilder sb = new StringBuilder("# Transcript\n\n");
        for (TranscriptSegment s : segments) {
            sb.append("- **").append(formatTime(s.startMs)).append("** ")
                    .append(s.text == null ? "" : s.text).append('\n');
        }
        return sb.toString();
    }

    static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long sec = totalSec % 60;
        return h > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", m, sec);
    }
}
