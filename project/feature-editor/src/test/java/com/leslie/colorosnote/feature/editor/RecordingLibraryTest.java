package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecordingLibraryTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** Every segment reports the same fixed duration. */
    private static RecordingLibrary.DurationProbe fixedProbe(long ms) {
        return audioFile -> ms;
    }

    private File segment(String name, long lastModified) throws IOException {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), new byte[]{1});
        assertTrue(f.setLastModified(lastModified));
        return f;
    }

    @Test
    public void missingDirYieldsEmptyList() {
        assertTrue(RecordingLibrary.scan(null, fixedProbe(0)).isEmpty());
        assertTrue(RecordingLibrary.scan(new File(tmp.getRoot(), "nope"), fixedProbe(0)).isEmpty());
    }

    @Test
    public void emptyDirYieldsEmptyList() {
        assertTrue(RecordingLibrary.scan(tmp.getRoot(), fixedProbe(0)).isEmpty());
    }

    @Test
    public void groupsSegmentsBySessionNewestFirst() throws IOException {
        segment("rec_100_part000.m4a", 1000L);
        segment("rec_100_part001.m4a", 2000L);
        segment("rec_200_part000.m4a", 5000L);
        // Legacy single-base sessions keep working.
        segment("rec_part000.m4a", 500L);

        List<RecordingLibrary.Recording> list =
                RecordingLibrary.scan(tmp.getRoot(), fixedProbe(60_000));
        assertEquals(3, list.size());
        assertEquals("rec_200", list.get(0).id); // newest first
        assertEquals("rec_100", list.get(1).id);
        assertEquals("rec", list.get(2).id);

        RecordingLibrary.Recording two = list.get(1);
        assertEquals(2, two.segmentCount());
        assertEquals(1000L, two.createdAtMs); // first segment's timestamp
        assertEquals(120_000L, two.durationMs); // durations summed
        assertTrue(two.segments.get(0).getName().endsWith("part000.m4a"));
    }

    @Test
    public void segmentsSortedByIndexNotByName() throws IOException {
        // 10+ segments would mis-sort lexicographically without index parsing.
        segment("rec_1_part010.m4a", 100L);
        segment("rec_1_part002.m4a", 100L);
        segment("rec_1_part000.m4a", 100L);

        List<RecordingLibrary.Recording> list =
                RecordingLibrary.scan(tmp.getRoot(), fixedProbe(0));
        assertEquals(1, list.size());
        List<File> segs = list.get(0).segments;
        assertEquals("rec_1_part000.m4a", segs.get(0).getName());
        assertEquals("rec_1_part002.m4a", segs.get(1).getName());
        assertEquals("rec_1_part010.m4a", segs.get(2).getName());
    }

    @Test
    public void nonSegmentFilesAreIgnored() throws IOException {
        segment("rec_1_part000.m4a", 100L);
        segment("notes.txt", 100L);
        segment("rec_1_part0.m4a", 100L);   // not 3 digits
        segment("rec_1_part001.mp3", 100L); // wrong extension

        List<RecordingLibrary.Recording> list =
                RecordingLibrary.scan(tmp.getRoot(), fixedProbe(0));
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).segmentCount());
    }

    @Test
    public void negativeProbeDurationsClampToZero() throws IOException {
        segment("rec_1_part000.m4a", 100L);
        segment("rec_1_part001.m4a", 100L);

        List<RecordingLibrary.Recording> list =
                RecordingLibrary.scan(tmp.getRoot(), audioFile -> -1);
        assertEquals(0L, list.get(0).durationMs);
    }

    @Test
    public void countSessionsWithoutProbing() throws IOException {
        assertEquals(0, RecordingLibrary.count(null));
        assertEquals(0, RecordingLibrary.count(tmp.getRoot()));
        segment("rec_100_part000.m4a", 1000L);
        segment("rec_100_part001.m4a", 2000L);
        segment("rec_200_part000.m4a", 5000L);
        segment("notes.txt", 100L);
        assertEquals(2, RecordingLibrary.count(tmp.getRoot()));
    }

    @Test
    public void deleteRemovesAllSegments() throws IOException {
        segment("rec_1_part000.m4a", 100L);
        segment("rec_1_part001.m4a", 100L);
        File other = segment("rec_2_part000.m4a", 100L);

        List<RecordingLibrary.Recording> list =
                RecordingLibrary.scan(tmp.getRoot(), fixedProbe(0));
        assertTrue(RecordingLibrary.delete(list.get(0)));
        for (File f : list.get(0).segments) {
            assertFalse(f.exists());
        }
        assertTrue(other.exists()); // other sessions untouched

        // Deleting again is fine (nothing left to fail on).
        assertTrue(RecordingLibrary.delete(list.get(0)));
    }
}
