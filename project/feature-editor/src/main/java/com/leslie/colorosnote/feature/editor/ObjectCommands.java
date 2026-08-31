package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.ink.Command;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Undoable property-edit commands for page objects (spec 18/19/30),
 * complementing {@code InkCommands.AddObject/RemoveObject} and
 * {@link EditorCommands.TransformObjects}. Each command captures the "before"
 * state at construction, so execute/undo are exact. Pure JVM.
 */
public final class ObjectCommands {

    private ObjectCommands() {
    }

    /** Locks/unlocks objects (locked objects are skipped by hit-testing). */
    public static final class SetLocked implements Command {
        private final List<PageObject> targets;
        private final boolean locked;
        private final boolean[] before;

        public SetLocked(List<PageObject> targets, boolean locked) {
            this.targets = new ArrayList<>(targets);
            this.locked = locked;
            before = new boolean[this.targets.size()];
            for (int i = 0; i < before.length; i++) {
                before[i] = this.targets.get(i).locked;
            }
        }

        @Override
        public void execute() {
            for (PageObject o : targets) {
                o.locked = locked;
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).locked = before[i];
            }
        }
    }

    /** Image opacity (spec 18.1 透明度). */
    public static final class SetImageOpacity implements Command {
        private final List<ImageObject> targets;
        private final float opacity;
        private final float[] before;

        public SetImageOpacity(List<ImageObject> targets, float opacity) {
            this.targets = new ArrayList<>(targets);
            this.opacity = Math.max(0f, Math.min(1f, opacity));
            before = new float[this.targets.size()];
            for (int i = 0; i < before.length; i++) {
                before[i] = this.targets.get(i).opacity;
            }
        }

        public SetImageOpacity(ImageObject target, float opacity) {
            this(Collections.singletonList(target), opacity);
        }

        @Override
        public void execute() {
            for (ImageObject o : targets) {
                o.opacity = opacity;
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).opacity = before[i];
            }
        }
    }

    /** Tape reveal state (spec 19 点击显示 / 本页全部遮盖 / 本页全部显示). */
    public static final class SetTapeRevealed implements Command {
        private final List<TapeObject> targets;
        private final boolean revealed;
        private final boolean[] before;

        public SetTapeRevealed(List<TapeObject> targets, boolean revealed) {
            this.targets = new ArrayList<>(targets);
            this.revealed = revealed;
            before = new boolean[this.targets.size()];
            for (int i = 0; i < before.length; i++) {
                before[i] = this.targets.get(i).isRevealed;
            }
        }

        public SetTapeRevealed(TapeObject target, boolean revealed) {
            this(Collections.singletonList(target), revealed);
        }

        @Override
        public void execute() {
            for (TapeObject o : targets) {
                o.isRevealed = revealed;
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).isRevealed = before[i];
            }
        }
    }

    /** zIndex changes (置顶/置底). */
    public static final class SetZIndex implements Command {
        private final List<PageObject> targets;
        private final int[] after;
        private final int[] before;

        public SetZIndex(List<PageObject> targets, int[] newZIndices) {
            if (targets.size() != newZIndices.length) {
                throw new IllegalArgumentException("targets/zIndices size mismatch");
            }
            this.targets = new ArrayList<>(targets);
            after = newZIndices.clone();
            before = new int[targets.size()];
            for (int i = 0; i < before.length; i++) {
                before[i] = targets.get(i).zIndex;
            }
        }

        public SetZIndex(PageObject target, int newZIndex) {
            this(Collections.singletonList(target), new int[]{newZIndex});
        }

        @Override
        public void execute() {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).zIndex = after[i];
            }
        }

        @Override
        public void undo() {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).zIndex = before[i];
            }
        }
    }

    /** Text box content/style edit (spec 18.2), snapshot-based. */
    public static final class EditTextBox implements Command {
        private final TextBoxObject target;
        private final Snapshot before;
        private final Snapshot after;

        public EditTextBox(TextBoxObject target, Snapshot after) {
            this.target = target;
            this.before = Snapshot.capture(target);
            this.after = after;
        }

        @Override
        public void execute() {
            after.apply(target);
        }

        @Override
        public void undo() {
            before.apply(target);
        }

        /** Immutable text box state, including bounds (auto-height changes). */
        public static final class Snapshot {
            public String text;
            public float fontSize;
            public int color;
            public boolean bold;
            public boolean italic;
            public boolean underline;
            public TextBoxObject.Alignment alignment;
            public float[] bounds;

            public static Snapshot capture(TextBoxObject box) {
                Snapshot s = new Snapshot();
                s.text = box.text;
                s.fontSize = box.fontSize;
                s.color = box.color;
                s.bold = box.bold;
                s.italic = box.italic;
                s.underline = box.underline;
                s.alignment = box.alignment;
                s.bounds = box.bounds.clone();
                return s;
            }

            void apply(TextBoxObject box) {
                box.text = text;
                box.fontSize = fontSize;
                box.color = color;
                box.bold = bold;
                box.italic = italic;
                box.underline = underline;
                box.alignment = alignment;
                box.bounds = bounds.clone();
            }
        }
    }
}
