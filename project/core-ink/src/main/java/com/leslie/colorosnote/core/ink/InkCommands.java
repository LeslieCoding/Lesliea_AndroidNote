package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in undoable commands for the ink editor (requirement 30). All pure
 * JVM; stroke transforms use {@link Stroke#transformValues(float[])} so no
 * Android Matrix is needed here.
 */
public final class InkCommands {

    private InkCommands() {
    }

    // ------------------------------------------------------------------
    // Strokes
    // ------------------------------------------------------------------

    /** Adds a stroke to the page (draw / paste). */
    public static final class AddStroke implements Command {
        private final Page page;
        private final Stroke stroke;

        public AddStroke(Page page, Stroke stroke) {
            this.page = page;
            this.stroke = stroke;
        }

        public Page page() {
            return page;
        }

        public Stroke stroke() {
            return stroke;
        }

        @Override
        public void execute() {
            if (!page.strokes.contains(stroke)) {
                page.strokes.add(stroke);
            }
        }

        @Override
        public void undo() {
            page.strokes.remove(stroke);
        }
    }

    /** Removes strokes from the page (erase / cut); undo restores order. */
    public static final class RemoveStroke implements Command {
        private final Page page;
        private final List<Stroke> strokes = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        public RemoveStroke(Page page, Stroke stroke) {
            this.page = page;
            this.strokes.add(stroke);
        }

        public RemoveStroke(Page page, List<Stroke> strokes) {
            this.page = page;
            this.strokes.addAll(strokes);
        }

        public Page page() {
            return page;
        }

        public List<Stroke> strokes() {
            return strokes;
        }

        @Override
        public void execute() {
            indices.clear();
            for (Stroke s : strokes) {
                indices.add(page.strokes.indexOf(s));
                page.strokes.remove(s);
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < strokes.size(); i++) {
                Stroke s = strokes.get(i);
                int idx = indices.get(i);
                if (idx < 0 || idx > page.strokes.size() || page.strokes.contains(s)) {
                    if (!page.strokes.contains(s)) {
                        page.strokes.add(s);
                    }
                } else {
                    page.strokes.add(idx, s);
                }
            }
        }
    }

    /**
     * Applies an affine transform (move/scale/rotate from the lasso) to a
     * stroke's points. The inverse is computed at construction so undo is
     * exact.
     *
     * @param m 9 floats in Android Matrix order (perspective ignored)
     */
    public static final class TransformStroke implements Command {
        private final Stroke stroke;
        private final float[] forward;
        private final float[] inverse;

        public TransformStroke(Stroke stroke, float[] m) {
            this.stroke = stroke;
            this.forward = m.clone();
            this.inverse = invertAffine(m);
        }

        @Override
        public void execute() {
            stroke.transformValues(forward);
        }

        @Override
        public void undo() {
            stroke.transformValues(inverse);
        }

        private static float[] invertAffine(float[] m) {
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
    }

    /** Changes stroke colors (lasso "change color"); undo restores per-stroke. */
    public static final class ChangeColor implements Command {
        private final List<Stroke> strokes;
        private final int newColor;
        private int[] oldColors;

        public ChangeColor(List<Stroke> strokes, int newColor) {
            this.strokes = new ArrayList<>(strokes);
            this.newColor = newColor;
        }

        @Override
        public void execute() {
            if (oldColors == null) {
                oldColors = new int[strokes.size()];
                for (int i = 0; i < strokes.size(); i++) {
                    oldColors[i] = strokes.get(i).color;
                }
            }
            for (Stroke s : strokes) {
                s.color = newColor;
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < strokes.size(); i++) {
                strokes.get(i).color = oldColors[i];
            }
        }
    }

    // ------------------------------------------------------------------
    // Objects
    // ------------------------------------------------------------------

    /** Adds an object to the page (insert image/text/shape/sticker/tape). */
    public static final class AddObject implements Command {
        private final Page page;
        private final PageObject object;

        public AddObject(Page page, PageObject object) {
            this.page = page;
            this.object = object;
        }

        @Override
        public void execute() {
            if (!page.objects.contains(object)) {
                page.objects.add(object);
            }
        }

        @Override
        public void undo() {
            page.objects.remove(object);
        }
    }

    /** Removes an object; undo restores it at its original index. */
    public static final class RemoveObject implements Command {
        private final Page page;
        private final PageObject object;
        private int index = -1;

        public RemoveObject(Page page, PageObject object) {
            this.page = page;
            this.object = object;
        }

        @Override
        public void execute() {
            index = page.objects.indexOf(object);
            page.objects.remove(object);
        }

        @Override
        public void undo() {
            if (!page.objects.contains(object)) {
                if (index < 0 || index > page.objects.size()) {
                    page.objects.add(object);
                } else {
                    page.objects.add(index, object);
                }
            }
        }
    }

    /**
     * Translates objects by (dx, dy). Consecutive moves of the same target
     * set merge, so a drag produces a single history entry even outside an
     * explicit transaction.
     */
    public static final class MoveObjects implements Command {
        private final List<PageObject> targets;
        private float dx;
        private float dy;

        public MoveObjects(List<PageObject> targets, float dx, float dy) {
            this.targets = new ArrayList<>(targets);
            this.dx = dx;
            this.dy = dy;
        }

        @Override
        public void execute() {
            apply(dx, dy);
        }

        @Override
        public void undo() {
            apply(-dx, -dy);
        }

        @Override
        public boolean mergeWith(Command next) {
            if (!(next instanceof MoveObjects)) {
                return false;
            }
            MoveObjects o = (MoveObjects) next;
            if (!targets.equals(o.targets)) {
                return false;
            }
            dx += o.dx;
            dy += o.dy;
            return true;
        }

        private void apply(float mx, float my) {
            for (PageObject o : targets) {
                o.transform[2] += mx; // MTRANS_X
                o.transform[5] += my; // MTRANS_Y
            }
        }
    }

    /** A group of commands executed/undone as one unit (drag transaction). */
    public static final class Composite implements Command {
        private final List<Command> commands;

        public Composite(List<Command> commands) {
            this.commands = new ArrayList<>(commands);
        }

        public int size() {
            return commands.size();
        }

        /** The member commands, in execution order (for listeners recursing). */
        public List<Command> commands() {
            return commands;
        }

        @Override
        public void execute() {
            for (Command c : commands) {
                c.execute();
            }
        }

        @Override
        public void undo() {
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undo();
            }
        }
    }
}
