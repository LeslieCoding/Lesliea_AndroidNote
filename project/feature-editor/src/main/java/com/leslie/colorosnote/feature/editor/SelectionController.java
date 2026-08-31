package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.ink.InkCommands;
import com.leslie.colorosnote.core.ink.LassoEngine;
import com.leslie.colorosnote.core.ink.UndoRedoStack;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the active lasso selection and implements the contextual operations
 * (spec 16): move/scale/rotate via incremental transform commands (wrapped in
 * one undo transaction per gesture by the caller), copy/cut/paste/delete and
 * change-color. The clipboard is editor-internal and deep-copied.
 */
public final class SelectionController {

    /** Paste offset so a pasted copy doesn't exactly cover the original. */
    private static final float PASTE_OFFSET = 24f;

    public interface Listener {
        /** Selection became active/empty; update overlay and action bar. */
        void onSelectionChanged();

        /** Selection content changed; refresh rendering and mark dirty. */
        void onContentMutated();
    }

    private Page page;
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<PageObject> objects = new ArrayList<>();
    private final List<Stroke> clipboardStrokes = new ArrayList<>();
    private final List<PageObject> clipboardObjects = new ArrayList<>();
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean hasSelection() {
        return !strokes.isEmpty() || !objects.isEmpty();
    }

    public List<Stroke> strokes() {
        return strokes;
    }

    public List<PageObject> objects() {
        return objects;
    }

    public void setSelection(Page targetPage, LassoEngine.Selection selection) {
        page = targetPage;
        strokes.clear();
        objects.clear();
        if (selection != null) {
            strokes.addAll(selection.strokes);
            objects.addAll(selection.objects);
        }
        notifySelectionChanged();
    }

    public void clear() {
        if (!hasSelection()) {
            return;
        }
        strokes.clear();
        objects.clear();
        notifySelectionChanged();
    }

    /** Union bounds of the selection in page coordinates, or null when empty. */
    public float[] boundsPage() {
        if (!hasSelection()) {
            return null;
        }
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (Stroke s : strokes) {
            float[] sb = s.computeBounds();
            l = Math.min(l, sb[0]);
            t = Math.min(t, sb[1]);
            r = Math.max(r, sb[2]);
            b = Math.max(b, sb[3]);
        }
        for (PageObject o : objects) {
            android.graphics.RectF wb = o.worldBounds();
            l = Math.min(l, wb.left);
            t = Math.min(t, wb.top);
            r = Math.max(r, wb.right);
            b = Math.max(b, wb.bottom);
        }
        return new float[]{l, t, r, b};
    }

    public boolean containsPagePoint(float x, float y) {
        float[] b = boundsPage();
        return b != null && x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3];
    }

    /**
     * Applies one incremental affine step to everything selected. Executes
     * undoable commands immediately — wrap a drag in
     * {@code stack.beginTransaction()/endTransaction()} so the whole gesture
     * becomes a single history entry (spec 30).
     */
    public void applyTransformStep(UndoRedoStack stack, float[] m9) {
        if (!hasSelection()) {
            return;
        }
        for (Stroke s : strokes) {
            stack.execute(new InkCommands.TransformStroke(s, m9));
        }
        if (!objects.isEmpty()) {
            stack.execute(new EditorCommands.TransformObjects(objects, m9));
        }
        notifyContentMutated();
    }

    // ------------------------------------------------------------------
    // Contextual operations
    // ------------------------------------------------------------------

    public void copy() {
        clipboardStrokes.clear();
        clipboardObjects.clear();
        for (Stroke s : strokes) {
            clipboardStrokes.add(ModelCopier.copyStroke(s));
        }
        for (PageObject o : objects) {
            PageObject c = ModelCopier.copyObject(o);
            if (c != null) {
                clipboardObjects.add(c);
            }
        }
    }

    public void cut(UndoRedoStack stack) {
        copy();
        delete(stack);
    }

    public boolean hasClipboard() {
        return !clipboardStrokes.isEmpty() || !clipboardObjects.isEmpty();
    }

    /** Pastes the clipboard onto the target page, offset, and selects it. */
    public void paste(UndoRedoStack stack, Page target) {
        if (!hasClipboard()) {
            return;
        }
        List<Stroke> pastedStrokes = new ArrayList<>();
        List<PageObject> pastedObjects = new ArrayList<>();
        stack.beginTransaction();
        try {
            for (Stroke s : clipboardStrokes) {
                Stroke copy = ModelCopier.copyStroke(s);
                copy.transformValues(translation(PASTE_OFFSET, PASTE_OFFSET));
                stack.execute(new InkCommands.AddStroke(target, copy));
                pastedStrokes.add(copy);
            }
            for (PageObject o : clipboardObjects) {
                PageObject copy = ModelCopier.copyObject(o);
                if (copy == null) {
                    continue;
                }
                copy.transform = EditorCommands.multiply(
                        translation(PASTE_OFFSET, PASTE_OFFSET), copy.transform);
                stack.execute(new InkCommands.AddObject(target, copy));
                pastedObjects.add(copy);
            }
        } finally {
            stack.endTransaction();
        }
        page = target;
        strokes.clear();
        strokes.addAll(pastedStrokes);
        objects.clear();
        objects.addAll(pastedObjects);
        notifySelectionChanged();
        notifyContentMutated();
    }

    public void delete(UndoRedoStack stack) {
        if (!hasSelection() || page == null) {
            return;
        }
        stack.beginTransaction();
        try {
            if (!strokes.isEmpty()) {
                stack.execute(new InkCommands.RemoveStroke(page, new ArrayList<>(strokes)));
            }
            for (PageObject o : new ArrayList<>(objects)) {
                // Double safety (v1.4.6): locked objects are never deleted,
                // even if they somehow ended up in the selection.
                if (o.locked) {
                    continue;
                }
                stack.execute(new InkCommands.RemoveObject(page, o));
            }
        } finally {
            stack.endTransaction();
        }
        clear();
        notifyContentMutated();
    }

    public void changeColor(UndoRedoStack stack, int color) {
        if (strokes.isEmpty()) {
            return;
        }
        stack.execute(new InkCommands.ChangeColor(strokes, color));
        notifyContentMutated();
    }

    // ------------------------------------------------------------------

    static float[] translation(float dx, float dy) {
        return new float[]{1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f};
    }

    private void notifySelectionChanged() {
        if (listener != null) {
            listener.onSelectionChanged();
        }
    }

    private void notifyContentMutated() {
        if (listener != null) {
            listener.onContentMutated();
        }
    }
}
