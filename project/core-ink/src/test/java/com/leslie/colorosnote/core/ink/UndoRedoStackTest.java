package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.TextBoxObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Capacity, execute/undo/redo, transaction merging, command coalescing. */
public class UndoRedoStackTest {

    @Test
    public void addStroke_executeUndoRedo() {
        Page page = new Page();
        Stroke stroke = new Stroke();
        UndoRedoStack stack = new UndoRedoStack();

        stack.execute(new InkCommands.AddStroke(page, stroke));
        assertEquals(1, page.strokes.size());
        assertTrue(stack.canUndo());

        stack.undo();
        assertEquals(0, page.strokes.size());
        assertTrue(stack.canRedo());

        stack.redo();
        assertEquals(1, page.strokes.size());
    }

    @Test
    public void removeStroke_undoRestoresOrder() {
        Page page = new Page();
        Stroke a = new Stroke();
        Stroke b = new Stroke();
        Stroke c = new Stroke();
        page.strokes.add(a);
        page.strokes.add(b);
        page.strokes.add(c);
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.RemoveStroke(page, b));
        assertEquals(2, page.strokes.size());
        stack.undo();
        assertEquals(3, page.strokes.size());
        assertEquals(b, page.strokes.get(1));
    }

    @Test
    public void capacity_dropsOldestEntries() {
        UndoRedoStack stack = new UndoRedoStack(); // 100
        final int[] counter = {0};
        for (int i = 0; i < 110; i++) {
            stack.execute(new Command() {
                @Override
                public void execute() {
                    counter[0]++;
                }

                @Override
                public void undo() {
                    counter[0]--;
                }
            });
        }
        assertEquals(100, stack.undoSize());
        for (int i = 0; i < 100; i++) {
            stack.undo();
        }
        assertEquals(10, counter[0]); // oldest 10 were dropped, never undone
        assertFalse(stack.canUndo());
    }

    @Test
    public void transaction_mergesDragIntoSingleUndo() {
        Page page = new Page();
        TextBoxObject box = new TextBoxObject();
        page.objects.add(box);
        List<PageObject> targets = Arrays.<PageObject>asList(box);
        UndoRedoStack stack = new UndoRedoStack();

        stack.beginTransaction();
        for (int i = 0; i < 10; i++) {
            stack.execute(new InkCommands.MoveObjects(targets, 5f, 2f));
        }
        stack.endTransaction();

        assertEquals(50f, box.transform[2], 1e-4f);
        assertEquals(20f, box.transform[5], 1e-4f);
        assertEquals(1, stack.undoSize());

        stack.undo();
        assertEquals(0f, box.transform[2], 1e-4f);
        assertEquals(0f, box.transform[5], 1e-4f);
    }

    @Test
    public void moveCommands_coalesceWithoutTransaction() {
        Page page = new Page();
        TextBoxObject box = new TextBoxObject();
        page.objects.add(box);
        List<PageObject> targets = Arrays.<PageObject>asList(box);
        UndoRedoStack stack = new UndoRedoStack();

        stack.execute(new InkCommands.MoveObjects(targets, 1f, 0f));
        stack.execute(new InkCommands.MoveObjects(targets, 2f, 0f));
        assertEquals(1, stack.undoSize()); // merged into one entry
        assertEquals(3f, box.transform[2], 1e-4f);
        stack.undo();
        assertEquals(0f, box.transform[2], 1e-4f);
    }

    @Test
    public void transformStroke_undoRestoresPoints() {
        Stroke stroke = new Stroke();
        stroke.points.add(new com.leslie.colorosnote.core.model.StrokePoint(10f, 20f, 0L, 0.5f));
        // Translate +5/+7 as 9-float matrix values.
        float[] m = {1f, 0f, 5f, 0f, 1f, 7f, 0f, 0f, 1f};
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.TransformStroke(stroke, m));
        assertEquals(15f, stroke.points.get(0).x, 1e-4f);
        assertEquals(27f, stroke.points.get(0).y, 1e-4f);
        stack.undo();
        assertEquals(10f, stroke.points.get(0).x, 1e-4f);
        assertEquals(20f, stroke.points.get(0).y, 1e-4f);
    }

    @Test
    public void changeColor_undoRestoresPerStroke() {
        Stroke a = new Stroke();
        a.color = 0xFF000000;
        Stroke b = new Stroke();
        b.color = 0xFFFF0000;
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.ChangeColor(Arrays.asList(a, b), 0xFF00FF00));
        assertEquals(0xFF00FF00, a.color);
        stack.undo();
        assertEquals(0xFF000000, a.color);
        assertEquals(0xFFFF0000, b.color);
    }

    @Test
    public void newEdit_clearsRedoBranch() {
        Page page = new Page();
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        stack.undo();
        assertTrue(stack.canRedo());
        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        assertFalse(stack.canRedo());
    }

    // ------------------------------------------------------------------
    // History listener (journal mirroring hook)
    // ------------------------------------------------------------------

    private static final class RecordingListener implements UndoRedoStack.Listener {
        final List<String> events = new ArrayList<>();
        final List<Command> applied = new ArrayList<>();
        final List<Command> undone = new ArrayList<>();

        @Override
        public void onApplied(Command command) {
            events.add("applied");
            applied.add(command);
        }

        @Override
        public void onUndone(Command command) {
            events.add("undone");
            undone.add(command);
        }
    }

    @Test
    public void listener_seesExecuteUndoRedo() {
        Page page = new Page();
        UndoRedoStack stack = new UndoRedoStack();
        RecordingListener listener = new RecordingListener();
        stack.setListener(listener);

        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        stack.undo();
        stack.redo();

        assertEquals(Arrays.asList("applied", "undone", "applied"), listener.events);
    }

    @Test
    public void listener_transactionFiresMembersThenCompositeUndo() {
        Page page = new Page();
        UndoRedoStack stack = new UndoRedoStack();
        RecordingListener listener = new RecordingListener();
        stack.setListener(listener);

        stack.beginTransaction();
        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        stack.endTransaction();
        assertEquals(2, listener.applied.size());

        stack.undo();
        assertEquals(1, listener.undone.size());
        assertTrue(listener.undone.get(0) instanceof InkCommands.Composite);

        stack.redo();
        assertEquals(3, listener.applied.size());
        assertTrue(listener.applied.get(2) instanceof InkCommands.Composite);
    }

    @Test
    public void listener_nullIsSafe() {
        Page page = new Page();
        UndoRedoStack stack = new UndoRedoStack();
        stack.execute(new InkCommands.AddStroke(page, new Stroke()));
        stack.undo(); // no listener registered: must not throw
        assertEquals(0, page.strokes.size());
    }
}
