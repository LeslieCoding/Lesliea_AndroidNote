package com.leslie.colorosnote.feature.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists and deletes the per-document recording sessions stored under
 * {@code filesDir/audio/<documentId>/} (spec 20). A session is a group of
 * rolling segment files named {@code <base>_partNNN.m4a}; the base name
 * doubles as the session id (unique per recording session). Pure JVM logic —
 * the Android-specific duration probe is injected, so this is unit-testable.
 */
public final class RecordingLibrary {

    /** Supplies the duration of one audio segment. */
    public interface DurationProbe {
        /** Returns the segment duration in ms, or 0 when unreadable. */
        long durationMs(File audioFile);
    }

    /** One recording session: ordered segment files plus display metadata. */
    public static final class Recording {
        /** Session id = segment base name (e.g. {@code rec_1724234567890}). */
        public final String id;
        /** Segment files ordered by segment index. */
        public final List<File> segments;
        /** Last-modified of the first segment (≈ session start). */
        public final long createdAtMs;
        /** Sum of probed segment durations. */
        public final long durationMs;

        Recording(String id, List<File> segments, long createdAtMs, long durationMs) {
            this.id = id;
            this.segments = segments;
            this.createdAtMs = createdAtMs;
            this.durationMs = durationMs;
        }

        public int segmentCount() {
            return segments.size();
        }
    }

    private static final Pattern SEGMENT_NAME = Pattern.compile("(.+)_part(\\d{3})\\.m4a");

    private RecordingLibrary() {
    }

    /**
     * Groups segment files into sessions, newest first. A missing/empty dir
     * yields an empty list; non-segment files are ignored.
     */
    public static List<Recording> scan(File dir, DurationProbe probe) {
        List<Recording> result = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return result;
        }
        Map<String, List<IndexedFile>> groups = new LinkedHashMap<>();
        for (File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            Matcher m = SEGMENT_NAME.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            String base = m.group(1);
            int index = Integer.parseInt(m.group(2));
            List<IndexedFile> group = groups.get(base);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(base, group);
            }
            group.add(new IndexedFile(index, f));
        }
        for (Map.Entry<String, List<IndexedFile>> e : groups.entrySet()) {
            List<IndexedFile> group = e.getValue();
            group.sort((a, b) -> Integer.compare(a.index, b.index));
            List<File> segments = new ArrayList<>(group.size());
            for (IndexedFile indexed : group) {
                segments.add(indexed.file);
            }
            long durationMs = 0;
            for (File segment : segments) {
                durationMs += Math.max(0L, probe.durationMs(segment));
            }
            result.add(new Recording(e.getKey(), segments,
                    segments.get(0).lastModified(), durationMs));
        }
        result.sort((a, b) -> Long.compare(b.createdAtMs, a.createdAtMs));
        return result;
    }

    /**
     * Counts distinct recording sessions without probing durations — cheap
     * enough to gate the record button's chooser.
     */
    public static int count(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0;
        }
        java.util.Set<String> bases = new java.util.HashSet<>();
        for (File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            Matcher m = SEGMENT_NAME.matcher(f.getName());
            if (m.matches()) {
                bases.add(m.group(1));
            }
        }
        return bases.size();
    }

    /** Deletes every segment file; returns true only when nothing is left. */
    public static boolean delete(Recording recording) {
        boolean ok = true;
        for (File f : recording.segments) {
            if (f.exists() && !f.delete()) {
                ok = false;
            }
        }
        return ok;
    }

    private static final class IndexedFile {
        final int index;
        final File file;

        IndexedFile(int index, File file) {
            this.index = index;
            this.file = file;
        }
    }
}
