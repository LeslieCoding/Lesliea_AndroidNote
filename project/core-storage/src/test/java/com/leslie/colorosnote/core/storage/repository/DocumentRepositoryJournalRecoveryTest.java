package com.leslie.colorosnote.core.storage.repository;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Crash-recovery integration (requirement 10) for the undo-resurrection bug:
 * a stroke whose undo/erase was not followed by a successful full save must
 * not come back when the journal replays after a process kill.
 *
 * <p>Pure JVM: uses the container-only constructor (no Room metadata). A
 * "process kill" is simulated by abandoning the repository/document and
 * reopening from the same directory with a fresh instance.
 */
public class DocumentRepositoryJournalRecoveryTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private DocumentRepository repository;

    @Before
    public void setUp() {
        repository = new DocumentRepository(tmp.getRoot());
    }

    private static Stroke strokeWith(String id) {
        Stroke stroke = new Stroke();
        stroke.id = id;
        stroke.color = 0xFF000000;
        stroke.points.add(new StrokePoint(1f, 1f, 1000L, 0.5f));
        return stroke;
    }

    private NoteDocument reopen(String documentId) throws Exception {
        // Fresh instance = new process; openDocument replays the journal.
        return new DocumentRepository(tmp.getRoot()).openDocument(documentId);
    }

    @Test
    public void undoneStrokeDoesNotResurrectAfterProcessDeath() throws Exception {
        NoteDocument doc = repository.createDocument("t", null);
        Page page = doc.pages.get(0);
        Stroke stroke = strokeWith("s1");

        // Stroke finishes: added to the page and journaled (fsync).
        page.strokes.add(stroke);
        repository.journalFor(doc.id).appendStroke(page.id, stroke);
        // Undo: in-memory removal + journal tombstone. The process dies here,
        // before the debounced full save could persist and compact.
        page.strokes.remove(stroke);
        repository.journalFor(doc.id).appendStrokeDelete(page.id, stroke.id);

        NoteDocument reopened = reopen(doc.id);
        assertTrue(reopened.pages.get(0).strokes.isEmpty());
    }

    @Test
    public void savedThenUndoneStrokeDoesNotResurrect() throws Exception {
        // Draw → full save (journal compacted) → undo → kill → reopen: the
        // container holds the stroke, only the tombstone is journaled.
        NoteDocument doc = repository.createDocument("t", null);
        Page page = doc.pages.get(0);
        Stroke stroke = strokeWith("s1");
        page.strokes.add(stroke);
        repository.saveDocument(doc);

        page.strokes.remove(stroke);
        repository.journalFor(doc.id).appendStrokeDelete(page.id, stroke.id);

        NoteDocument reopened = reopen(doc.id);
        assertTrue(reopened.pages.get(0).strokes.isEmpty());
    }

    @Test
    public void committedStrokeSurvivesProcessDeath() throws Exception {
        // Control: draw → kill without undo → the stroke is recovered.
        NoteDocument doc = repository.createDocument("t", null);
        Page page = doc.pages.get(0);
        Stroke stroke = strokeWith("s1");
        page.strokes.add(stroke);
        repository.journalFor(doc.id).appendStroke(page.id, stroke);

        NoteDocument reopened = reopen(doc.id);
        assertEquals(1, reopened.pages.get(0).strokes.size());
        assertEquals("s1", reopened.pages.get(0).strokes.get(0).id);
    }

    @Test
    public void redoAfterUndoRevivesStrokeExactlyOnce() throws Exception {
        // Draw → undo → redo → kill → reopen: append + tombstone + append.
        NoteDocument doc = repository.createDocument("t", null);
        Page page = doc.pages.get(0);
        Stroke stroke = strokeWith("s1");
        page.strokes.add(stroke);
        repository.journalFor(doc.id).appendStroke(page.id, stroke);
        page.strokes.remove(stroke);
        repository.journalFor(doc.id).appendStrokeDelete(page.id, stroke.id);
        page.strokes.add(stroke);
        repository.journalFor(doc.id).appendStroke(page.id, stroke);

        NoteDocument reopened = reopen(doc.id);
        assertEquals(1, reopened.pages.get(0).strokes.size());
        assertEquals("s1", reopened.pages.get(0).strokes.get(0).id);
    }

    @Test
    public void saveAfterUndoCompactsTombstone() throws Exception {
        // Draw → undo → the debounced save succeeds → kill → reopen: nothing
        // to replay, the stroke stays gone.
        NoteDocument doc = repository.createDocument("t", null);
        Page page = doc.pages.get(0);
        Stroke stroke = strokeWith("s1");
        page.strokes.add(stroke);
        repository.journalFor(doc.id).appendStroke(page.id, stroke);
        page.strokes.remove(stroke);
        repository.journalFor(doc.id).appendStrokeDelete(page.id, stroke.id);
        repository.saveDocument(doc);

        NoteDocument reopened = reopen(doc.id);
        assertTrue(reopened.pages.get(0).strokes.isEmpty());
    }
}
