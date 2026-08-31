package com.leslie.colorosnote.feature.editor;

import java.util.Locale;

/**
 * Maps the brush-properties width SeekBar progress to a pen width in points.
 * Page logical units are points (A4 @ 72dpi → 595x842), so a width of 1.0
 * here is exactly 1pt. v1.4.19 lowered the adjustable floor from 1pt to
 * 0.5pt (~1 physical pixel on a 1600px-wide tablet at fit zoom) and raised
 * the ceiling from 40pt to 60pt, in 0.5pt steps. Pure JVM for unit tests.
 *
 * <p>v3.2.3: the fountain pen's slider floor is raised above 0.5pt. Its nib
 * is pressure/velocity-responsive and can collapse far below baseWidth, and
 * a 0.5pt body renders sub-device-pixel at fit zoom — faint enough that slow
 * short strokes occasionally "didn't register". Other (fixed-width) pens
 * keep the 0.5pt floor.
 *
 * <p>v3.2.5: the fountain-pen floor is tuned to 1.13pt — thin enough to look
 * like a fine nib, yet wide enough that the responsive nib (which can dip to
 * ~0.4x baseWidth) stays above one device pixel at fit zoom.
 */
public final class WidthSliderMapper {

    public static final float MIN_WIDTH = 0.5f;
    /**
     * Fountain-pen slider floor (v3.2.5: 1.13pt, see class doc).
     */
    public static final float FOUNTAIN_PEN_MIN_WIDTH = 1.13f;
    public static final float MAX_WIDTH = 60f;
    public static final float STEP = 0.5f;
    public static final int MAX_PROGRESS = Math.round((MAX_WIDTH - MIN_WIDTH) / STEP);

    private WidthSliderMapper() {
    }

    /** Width slider floor for a tool; only the fountain pen is raised. */
    public static float minWidthFor(EditorTool tool) {
        return tool == EditorTool.FOUNTAIN_PEN ? FOUNTAIN_PEN_MIN_WIDTH : MIN_WIDTH;
    }

    public static float progressToWidth(int progress) {
        return progressToWidth(progress, MIN_WIDTH);
    }

    public static float progressToWidth(int progress, float minWidth) {
        int maxProgress = maxProgress(minWidth);
        int p = Math.max(0, Math.min(maxProgress, progress));
        // The last step can overshoot MAX_WIDTH when (MAX_WIDTH - minWidth) is
        // not a multiple of STEP (e.g. the 1.13pt fountain floor); clamp so the
        // slider's maximum is always exactly MAX_WIDTH.
        return Math.min(MAX_WIDTH, minWidth + p * STEP);
    }

    public static int widthToProgress(float width) {
        return widthToProgress(width, MIN_WIDTH);
    }

    public static int widthToProgress(float width, float minWidth) {
        int maxProgress = maxProgress(minWidth);
        int p = Math.round((width - minWidth) / STEP);
        return Math.max(0, Math.min(maxProgress, p));
    }

    /** Slider progress that maps to MAX_WIDTH for the given floor. */
    public static int maxProgress(float minWidth) {
        return Math.round((MAX_WIDTH - minWidth) / STEP);
    }

    /** Display label for the current value, e.g. "3 pt" / "2.5 pt". */
    public static String label(float width) {
        return label(width, MIN_WIDTH);
    }

    /** Display label with a custom slider floor (fountain pen). */
    public static String label(float width, float minWidth) {
        float snapped = progressToWidth(widthToProgress(width, minWidth), minWidth);
        if (snapped == Math.floor(snapped)) {
            return String.format(Locale.US, "%d pt", (int) snapped);
        }
        // 1.13pt floor produces non-half-point values ("1.13 pt"); half points
        // stay one decimal ("2.5 pt").
        String s = String.format(Locale.US, "%.2f", snapped);
        if (s.endsWith("0")) {
            s = String.format(Locale.US, "%.1f", snapped);
        }
        return s + " pt";
    }
}
