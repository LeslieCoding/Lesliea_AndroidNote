package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Pure-JVM tests for the editor-level undo commands (spec 30). */
public class EditorCommandsTest {

    private static Stroke stroke(float x) {
        Stroke s = new Stroke();
        s.points.add(new StrokePoint(x, 0f, 0L, 1f));
        s.points.add(new StrokePoint(x + 10f, 10f, 1L, 1f));
        return s;
    }

    @Test
    public void replaceStrokesExecuteUndoRestoresLists() {
        Page page = new Page();
        Stroke a = stroke(0f);
        Stroke b = stroke(100f);
        page.strokes.add(a);
        page.strokes.add(b);

        Stroke cut = stroke(50f);
        EditorCommands.ReplaceStrokes cmd = new EditorCommands.ReplaceStrokes(
                page, Arrays.asList(a, b), Arrays.asList(a, cut));
        cmd.execute();
        assertEquals(Arrays.asList(a, cut), page.strokes);
        cmd.undo();
        assertEquals(Arrays.asList(a, b), page.strokes);
    }

    @Test
    public void addRemovePageReindexes() {
        NoteDocument doc = new NoteDocument();
        Page p0 = new Page();
        doc.pages.add(p0);
        p0.index = 0;

        Page p1 = new Page();
        EditorCommands.AddPage add = new EditorCommands.AddPage(doc, p1, 1);
        add.execute();
        assertEquals(2, doc.pages.size());
        assertEquals(0, doc.pages.get(0).index);
        assertEquals(1, doc.pages.get(1).index);
        add.undo();
        assertEquals(1, doc.pages.size());

        EditorCommands.RemovePage remove = new EditorCommands.RemovePage(doc, p0);
        remove.execute();
        assertTrue(doc.pages.isEmpty());
        remove.undo();
        assertEquals(p0, doc.pages.get(0));
        assertEquals(0, doc.pages.get(0).index);
    }

    @Test
    public void movePageRoundTrip() {
        NoteDocument doc = new NoteDocument();
        Page a = new Page();
        Page b = new Page();
        Page c = new Page();
        doc.pages.add(a);
        doc.pages.add(b);
        doc.pages.add(c);

        EditorCommands.MovePage move = new EditorCommands.MovePage(doc, 0, 2);
        move.execute();
        assertEquals(Arrays.asList(b, c, a), doc.pages);
        assertEquals(2, a.index);
        move.undo();
        assertEquals(Arrays.asList(a, b, c), doc.pages);
        assertEquals(0, a.index);
    }

    @Test
    public void changeBackgroundRoundTrip() {
        Page page = new Page();
        page.background = PageBackground.ofType(PageBackground.Type.BLANK);
        PageBackground lined = PageBackground.ofType(PageBackground.Type.LINED);

        EditorCommands.ChangeBackground cmd = new EditorCommands.ChangeBackground(page, lined);
        cmd.execute();
        assertEquals(PageBackground.Type.LINED, page.background.type);
        cmd.undo();
        assertEquals(PageBackground.Type.BLANK, page.background.type);
    }

    @Test
    public void transformObjectsRoundTripIsExact() {
        TextBoxObject o = new TextBoxObject();
        float[] translate = {1f, 0f, 30f, 0f, 1f, -12f, 0f, 0f, 1f};
        EditorCommands.TransformObjects cmd =
                new EditorCommands.TransformObjects(List.of(o), translate);
        cmd.execute();
        assertEquals(30f, o.transform[2], 1e-4f);
        assertEquals(-12f, o.transform[5], 1e-4f);
        cmd.undo();
        assertEquals(0f, o.transform[2], 1e-4f);
        assertEquals(0f, o.transform[5], 1e-4f);
        assertFalse(o.locked);
    }
}
