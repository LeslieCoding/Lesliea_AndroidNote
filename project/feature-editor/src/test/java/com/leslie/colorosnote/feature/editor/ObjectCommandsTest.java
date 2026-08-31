package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.ink.InkCommands;
import com.leslie.colorosnote.core.ink.UndoRedoStack;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Pure-JVM tests for the object property-edit commands (spec 18/19/30). */
public class ObjectCommandsTest {

    @Test
    public void addObjectViaStackUndoRedo() {
        Page page = new Page();
        TextBoxObject box = new TextBoxObject();
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.AddObject(page, box));
        assertEquals(1, page.objects.size());
        stack.undo();
        assertTrue(page.objects.isEmpty());
        stack.redo();
        assertEquals(1, page.objects.size());
    }

    @Test
    public void setZIndexUndoRestores() {
        TextBoxObject a = new TextBoxObject();
        a.zIndex = 3;
        ObjectCommands.SetZIndex cmd = new ObjectCommands.SetZIndex(a, 9);
        cmd.execute();
        assertEquals(9, a.zIndex);
        cmd.undo();
        assertEquals(3, a.zIndex);
    }

    @Test
    public void setTapeRevealedBatchUndoRestoresMixedState() {
        TapeObject t1 = new TapeObject();
        t1.isRevealed = false;
        TapeObject t2 = new TapeObject();
        t2.isRevealed = true;
        ObjectCommands.SetTapeRevealed cmd = new ObjectCommands.SetTapeRevealed(
                Arrays.asList(t1, t2), true);
        cmd.execute();
        assertTrue(t1.isRevealed);
        assertTrue(t2.isRevealed);
        cmd.undo();
        assertFalse(t1.isRevealed);
        assertTrue(t2.isRevealed);
    }

    @Test
    public void setLockedUndoRestores() {
        TextBoxObject box = new TextBoxObject();
        ObjectCommands.SetLocked cmd = new ObjectCommands.SetLocked(
                Collections.singletonList(box), true);
        cmd.execute();
        assertTrue(box.locked);
        cmd.undo();
        assertFalse(box.locked);
    }

    @Test
    public void setImageOpacityClampsAndUndoes() {
        ImageObject image = new ImageObject();
        image.opacity = 1f;
        ObjectCommands.SetImageOpacity cmd =
                new ObjectCommands.SetImageOpacity(image, 1.5f);
        cmd.execute();
        assertEquals(1f, image.opacity, 0.001f);
        ObjectCommands.SetImageOpacity half =
                new ObjectCommands.SetImageOpacity(image, 0.25f);
        half.execute();
        assertEquals(0.25f, image.opacity, 0.001f);
        half.undo();
        assertEquals(1f, image.opacity, 0.001f);
    }

    @Test
    public void editTextBoxSnapshotRoundTrip() {
        TextBoxObject box = new TextBoxObject();
        box.text = "旧文本";
        box.fontSize = 18f;
        box.bounds = new float[]{0f, 0f, 260f, 60f};

        ObjectCommands.EditTextBox.Snapshot after =
                ObjectCommands.EditTextBox.Snapshot.capture(box);
        after.text = "新文本\n第二行";
        after.bold = true;
        after.fontSize = 24f;
        after.bounds = new float[]{0f, 0f, 260f, 120f};

        ObjectCommands.EditTextBox cmd = new ObjectCommands.EditTextBox(box, after);
        cmd.execute();
        assertEquals("新文本\n第二行", box.text);
        assertTrue(box.bold);
        assertEquals(24f, box.fontSize, 0.001f);
        assertEquals(120f, box.bounds[3], 0.001f);

        cmd.undo();
        assertEquals("旧文本", box.text);
        assertFalse(box.bold);
        assertEquals(18f, box.fontSize, 0.001f);
        assertEquals(60f, box.bounds[3], 0.001f);
    }

    @Test
    public void snapshotCaptureIsIndependentOfLaterMutation() {
        TextBoxObject box = new TextBoxObject();
        box.text = "before";
        ObjectCommands.EditTextBox.Snapshot snap =
                ObjectCommands.EditTextBox.Snapshot.capture(box);
        box.text = "after";
        box.bounds[3] = 999f;
        assertEquals("before", snap.text);
        assertFalse(snap.bounds == box.bounds);
    }

    @Test
    public void editTextBoxBoundsMoveUndoRedoViaStack() {
        // Move-only edit, as committed by the text box drag gesture.
        TextBoxObject box = new TextBoxObject();
        box.text = "不动文本";
        box.bounds = new float[]{10f, 20f, 270f, 80f};

        ObjectCommands.EditTextBox.Snapshot after =
                ObjectCommands.EditTextBox.Snapshot.capture(box);
        after.bounds = new float[]{110f, 150f, 370f, 210f};

        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new ObjectCommands.EditTextBox(box, after));
        assertEquals(110f, box.bounds[0], 0.001f);
        assertEquals(210f, box.bounds[3], 0.001f);
        assertEquals("不动文本", box.text);

        stack.undo();
        assertEquals(10f, box.bounds[0], 0.001f);
        assertEquals(80f, box.bounds[3], 0.001f);

        stack.redo();
        assertEquals(110f, box.bounds[0], 0.001f);
        assertEquals(210f, box.bounds[3], 0.001f);
    }
}
