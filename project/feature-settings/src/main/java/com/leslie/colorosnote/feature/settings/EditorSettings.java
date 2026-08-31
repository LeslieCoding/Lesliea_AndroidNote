package com.leslie.colorosnote.feature.settings;

/**
 * User-tunable editor/gesture settings (spec 29). Pure data object; persisted
 * by {@link SettingsStore}.
 */
public final class EditorSettings {

    public enum FingerMode {
        /** Finger draws too. */
        DRAW,
        /** Finger only pans/scrolls; stylus writes (default for note-taking). */
        PAN_ONLY
    }

    /** Stylus side-button action. */
    public enum StylusButtonAction {
        ERASER, LASSO, NONE
    }

    public enum HandSide {
        RIGHT, LEFT
    }

    public FingerMode fingerMode = FingerMode.PAN_ONLY;
    /** 0..3 palm rejection strictness. */
    public int palmRejectionLevel = 2;
    public boolean twoFingerTapUndo = true;
    public boolean threeFingerTapRedo = true;
    public boolean longPressShapeFix = true;
    public StylusButtonAction stylusButtonAction = StylusButtonAction.ERASER;
    public boolean eraserTailAutoEraser = true;
    public HandSide toolbarSide = HandSide.RIGHT;
    /** Global pressure curve name: SOFT/BALANCED/FIRM/CUSTOM (per-brush override allowed). */
    public String globalPressureCurve = "BALANCED";
    /** Show "simulated pressure" indicator when hardware pressure missing. */
    public boolean showSimulatedPressureHint = true;
    /** Immersive mode: auto-hide toolbars while writing. Off by default (v1.1.7:
     *  user demand — bars must stay visible; toggle remains in the more menu). */
    public boolean immersiveMode = false;

    public EditorSettings() {
    }
}
