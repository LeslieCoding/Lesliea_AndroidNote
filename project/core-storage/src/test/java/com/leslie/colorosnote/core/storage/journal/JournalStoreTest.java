package com.leslie.colorosnote.core.storage.journal;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Journal append/replay/dedup/compact behavior (requirement 10). */
public class JournalStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static Stroke strokeWith(String id, float x) {
        Stroke stroke = new Stroke();
        stroke.id = id;
        stroke.color = 0xFF000000;
        stroke.points.add(new StrokePoint(x, 1f, 1000L, 0.5f));
        return stroke;
    }

    private static NoteDocument docWithPage(String pageId) {
        NoteDocument doc = new NoteDocument();
        Page page = new Page();
        page.id = pageId;
        doc.pages.add(page);
        return doc;
    }

    @Test
    public void replayAppliesPendingStrokes() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        store.appendStroke("p1", strokeWith("s2", 20f));
        assertTrue(store.hasPending());

        NoteDocument doc = docWithPage("p1");
        assertEquals(2, store.recover(doc));
        assertEquals(2, doc.pages.get(0).strokes.size());
        assertEquals(10f, doc.pages.get(0).strokes.get(0).points.get(0).x, 0f);
    }

    @Test
    public void replayIsIdempotentNoDuplicateStrokes() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));

        // Base document already contains the stroke (saved before the crash),
        // and the journal replays it again: must not duplicate.
        NoteDocument doc = docWithPage("p1");
        doc.pages.get(0).strokes.add(strokeWith("s1", 10f));
        assertEquals(1, store.recover(doc));
        assertEquals(1, doc.pages.get(0).strokes.size());
    }

    @Test
    public void sameStrokeIdAppendedTwiceLastWins() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        store.appendStroke("p1", strokeWith("s1", 99f)); // re-journaled edit

        NoteDocument doc = docWithPage("p1");
        assertEquals(2, store.recover(doc));
        assertEquals(1, doc.pages.get(0).strokes.size());
        assertEquals(99f, doc.pages.get(0).strokes.get(0).points.get(0).x, 0f);
    }

    @Test
    public void corruptTailIsTolerated() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        // Simulate a crash mid-append: truncated JSON line at the end.
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write("{\"op\":\"stroke\",\"page".getBytes(StandardCharsets.UTF_8));
        }
        NoteDocument doc = docWithPage("p1");
        assertEquals(1, store.recover(doc)); // valid prefix survives
        assertEquals(1, doc.pages.get(0).strokes.size());
    }

    @Test
    public void objectEditsReplayPolymorphically() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        TextBoxObject text = new TextBoxObject();
        text.id = "o1";
        text.text = "edited 文本";
        store.appendObject("p1", text);

        NoteDocument doc = docWithPage("p1");
        assertEquals(1, store.recover(doc));
        assertEquals(1, doc.pages.get(0).objects.size());
        assertTrue(doc.pages.get(0).objects.get(0) instanceof TextBoxObject);
        assertEquals("edited 文本", ((TextBoxObject) doc.pages.get(0).objects.get(0)).text);
    }

    @Test
    public void strokeForMissingPageIsSkipped() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("deleted-page", strokeWith("s1", 10f));
        NoteDocument doc = docWithPage("p1");
        assertEquals(0, store.recover(doc));
        assertTrue(doc.pages.get(0).strokes.isEmpty());
    }

    @Test
    public void compactDeletesJournal() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        assertTrue(journal.exists());
        store.compact();
        assertFalse(journal.exists());
        assertFalse(store.hasPending());
        // Recovering after compact is a no-op.
        assertEquals(0, store.recover(docWithPage("p1")));
    }

    @Test
    public void upsertStrokeKeepsPositionOnReplace() {
        Page page = new Page();
        page.strokes.add(strokeWith("a", 1f));
        page.strokes.add(strokeWith("b", 2f));
        JournalStore.upsertStroke(page, strokeWith("a", 42f));
        assertEquals(2, page.strokes.size());
        assertEquals("a", page.strokes.get(0).id);
        assertEquals(42f, page.strokes.get(0).points.get(0).x, 0f);
    }

    // ------------------------------------------------------------------
    // stroke_delete tombstones (undo/erase resurrection fix)
    // ------------------------------------------------------------------

    @Test
    public void tombstoneRemovesReplayedStroke() throws Exception {
        // Draw → undo → process killed before the debounced full save: the
        // journal holds append + tombstone, so recovery must not resurrect.
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        store.appendStrokeDelete("p1", "s1");

        NoteDocument doc = docWithPage("p1");
        assertEquals(2, store.recover(doc));
        assertTrue(doc.pages.get(0).strokes.isEmpty());
    }

    @Test
    public void tombstoneDeletesStrokeLoadedFromContainer() throws Exception {
        // The stroke was saved into the container earlier; only the undo's
        // tombstone is in the journal (crash before the post-undo save).
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStrokeDelete("p1", "s1");

        NoteDocument doc = docWithPage("p1");
        doc.pages.get(0).strokes.add(strokeWith("s1", 10f));
        assertEquals(1, store.recover(doc));
        assertTrue(doc.pages.get(0).strokes.isEmpty());
    }

    @Test
    public void reAppendAfterTombstoneRevivesStrokeExactlyOnce() throws Exception {
        // Redo after undo: append → delete → append; replay must leave the
        // stroke present exactly once.
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStroke("p1", strokeWith("s1", 10f));
        store.appendStrokeDelete("p1", "s1");
        store.appendStroke("p1", strokeWith("s1", 10f));

        NoteDocument doc = docWithPage("p1");
        assertEquals(3, store.recover(doc));
        assertEquals(1, doc.pages.get(0).strokes.size());
        assertEquals("s1", doc.pages.get(0).strokes.get(0).id);
    }

    @Test
    public void tombstoneForUnknownStrokeIsNoOp() throws Exception {
        File journal = new File(tmp.getRoot(), "d.journal");
        JournalStore store = new JournalStore(journal);
        store.appendStrokeDelete("p1", "ghost");
        NoteDocument doc = docWithPage("p1");
        assertEquals(0, store.recover(doc));
        assertTrue(doc.pages.get(0).strokes.isEmpty());
    }
}
