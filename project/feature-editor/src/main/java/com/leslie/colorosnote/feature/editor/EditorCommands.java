package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.ink.Command;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-level undo commands complementing {@code InkCommands} (spec 30):
 * stroke-list replacement (erase gestures), page add/remove/reorder, page
 * background switch, and affine transforms of page objects. All main-thread
 * lightweight; nothing here does IO.
 */
public final class EditorCommands {

    private EditorCommands() {
    }

    // ------------------------------------------------------------------
    // Strokes
    // ------------------------------------------------------------------

    /**
     * Replaces the whole stroke list of a page. Used by erase gestures which
     * mutate the list live during the gesture; execute() re-applies the same
     * end state so pushing the command after the gesture is safe.
     */
    public static final class ReplaceStrokes implements Command {
        private final Page page;
        private final List<Stroke> before;
        private final List<Stroke> after;

        public ReplaceStrokes(Page page, List<Stroke> before, List<Stroke> after) {
            this.page = page;
            this.before = new ArrayList<>(before);
            this.after = new ArrayList<>(after);
        }

        public Page page() {
            return page;
        }

        public List<Stroke> before() {
            return before;
        }

        public List<Stroke> after() {
            return after;
        }

        @Override
        public void execute() {
            apply(after);
        }

        @Override
        public void undo() {
            apply(before);
        }

        private void apply(List<Stroke> target) {
            page.strokes.clear();
            page.strokes.addAll(target);
        }
    }

    // ------------------------------------------------------------------
    // Objects
    // ------------------------------------------------------------------

    /**
     * Affine transform (move/scale/rotate from the lasso) applied to page
     * objects: {@code transform = forward * transform}. The inverse is computed
     * at construction so undo is exact.
     */
    public static final class TransformObjects implements Command {
        private final List<PageObject> targets;
        private final float[] forward;
        private final float[] inverse;

        public TransformObjects(List<PageObject> targets, float[] m9) {
            this.targets = new ArrayList<>(targets);
            this.forward = m9.clone();
            this.inverse = invertAffine(m9);
        }

        @Override
        public void execute() {
            for (PageObject o : targets) {
                o.transform = multiply(forward, o.transform);
            }
        }

        @Override
        public void undo() {
            for (PageObject o : targets) {
                o.transform = multiply(inverse, o.transform);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    /** Inserts a page at an index (add page / duplicate / PDF blank insert). */
    public static final class AddPage implements Command {
        private final NoteDocument document;
        private final Page page;
        private final int index;

        public AddPage(NoteDocument document, Page page, int index) {
            this.document = document;
            this.page = page;
            this.index = index;
        }

        @Override
        public void execute() {
            int at = Math.max(0, Math.min(index, document.pages.size()));
            if (!document.pages.contains(page)) {
                document.pages.add(at, page);
            }
            reindex(document);
        }

        @Override
        public void undo() {
            document.pages.remove(page);
            reindex(document);
        }
    }

    /** Removes a page; undo restores it at its original position. */
    public static final class RemovePage implements Command {
        private final NoteDocument document;
        private final Page page;
        private int index = -1;

        public RemovePage(NoteDocument document, Page page) {
            this.document = document;
            this.page = page;
        }

        @Override
        public void execute() {
            index = document.pages.indexOf(page);
            document.pages.remove(page);
            reindex(document);
        }

        @Override
        public void undo() {
            if (!document.pages.contains(page)) {
                int at = index < 0 || index > document.pages.size()
                        ? document.pages.size() : index;
                document.pages.add(at, page);
            }
            reindex(document);
        }
    }

    /** Moves a page from one index to another (page reorder, spec 30). */
    public static final class MovePage implements Command {
        private final NoteDocument document;
        private final int from;
        private final int to;

        public MovePage(NoteDocument document, int from, int to) {
            this.document = document;
            this.from = from;
            this.to = to;
        }

        @Override
        public void execute() {
            move(document, from, to);
        }

        @Override
        public void undo() {
            move(document, to, from);
        }

        private static void move(NoteDocument doc, int a, int b) {
            if (a < 0 || a >= doc.pages.size() || b < 0 || b >= doc.pages.size()) {
                return;
            }
            Page p = doc.pages.remove(a);
            doc.pages.add(b, p);
            reindex(doc);
        }
    }

    /** Switches a page's background template; undo restores the old one. */
    public static final class ChangeBackground implements Command {
        private final Page page;
        private final PageBackground before;
        private final PageBackground after;

        public ChangeBackground(Page page, PageBackground after) {
            this.page = page;
            this.before = ModelCopier.copyBackground(page.background);
            this.after = after;
        }

        @Override
        public void execute() {
            page.background = ModelCopier.copyBackground(after);
        }

        @Override
        public void undo() {
            page.background = ModelCopier.copyBackground(before);
        }
    }

    // ------------------------------------------------------------------
    // Affine helpers (3x3, Android Matrix values order)
    // ------------------------------------------------------------------

    static float[] multiply(float[] a, float[] b) {
        float[] r = new float[9];
        r[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6];
        r[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7];
        r[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8];
        r[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6];
        r[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7];
        r[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8];
        r[6] = 0f;
        r[7] = 0f;
        r[8] = 1f;
        return r;
    }

    static float[] invertAffine(float[] m) {
        float a = m[0], b = m[1], c = m[2];
        float d = m[3], e = m[4], f = m[5];
        float det = a * e - b * d;
        if (Math.abs(det) < 1e-8f) {
            throw new IllegalArgumentException("Non-invertible transform");
        }
        float invDet = 1f / det;
        float ia = e * invDet;
        float ib = -b * invDet;
        float id = -d * invDet;
        float ie = a * invDet;
        float ic = -(ia * c + ib * f);
        float if_ = -(id * c + ie * f);
        return new float[]{ia, ib, ic, id, ie, if_, 0f, 0f, 1f};
    }

    static void reindex(NoteDocument document) {
        for (int i = 0; i < document.pages.size(); i++) {
            document.pages.get(i).index = i;
        }
    }
}
