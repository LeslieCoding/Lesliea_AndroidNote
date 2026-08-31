package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TranscriptStoreTest {

    @Test
    public void keepsOrderByStart() {
        TranscriptStore store = new TranscriptStore();
        store.add(new TranscriptSegment("2", "later", 5000, 6000));
        store.add(new TranscriptSegment("1", "first", 0, 1000));
        assertEquals("1", store.all().get(0).id);
    }

    @Test
    public void segmentAt() {
        TranscriptStore store = new TranscriptStore();
        store.add(new TranscriptSegment("1", "hello", 1000, 2000));
        assertNotNull(store.segmentAt(1500));
        assertEquals(null, store.segmentAt(3000));
    }

    @Test
    public void exportTxtFormat() {
        TranscriptStore store = new TranscriptStore();
        store.add(new TranscriptSegment("1", "你好", 61000, 62000));
        String txt = store.exportTxt();
        assertTrue(txt.contains("[01:01] 你好"));
    }

    @Test
    public void exportMarkdownFormat() {
        TranscriptStore store = new TranscriptStore();
        store.add(new TranscriptSegment("1", "note", 0, 500));
        String md = store.exportMarkdown();
        assertTrue(md.startsWith("# Transcript"));
        assertTrue(md.contains("**00:00**"));
    }

    @Test
    public void editAndRemove() {
        TranscriptStore store = new TranscriptStore();
        store.add(new TranscriptSegment("1", "old", 0, 100));
        assertTrue(store.updateText("1", "new"));
        assertEquals("new", store.all().get(0).text);
        assertTrue(store.remove("1"));
        assertFalse(store.remove("1"));
    }
}
