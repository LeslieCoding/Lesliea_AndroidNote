package com.leslie.colorosnote.feature.editor;

import java.util.Locale;

/**
 * Maps the brush-properties width SeekBar progress to a pen width in points.
 * Page logical units are points (A4 @ 72dpi → 595x842), so a width of 1.0
 * here is exactly 1pt. v1.4.19 lowered the adjustable floor from 1pt to
 * 0.5pt (~1 physical pixel on a 1600px-wide tablet at fit zoom) and raised
 * the ceiling from 40pt to 60pt, in 0.5pt steps. Pure JVM for unit tests.
 */
public final class WidthSliderMapper {

    public static final float MIN_WIDTH = 0.5f;
    public static final float MAX_WIDTH = 60f;
    public static final float STEP = 0.5f;
    public static final int MAX_PROGRESS = Math.round((MAX_WIDTH - MIN_WIDTH) / STEP);

    private WidthSliderMapper() {
    }

    public static float progressToWidth(int progress) {
        int p = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return MIN_WIDTH + p * STEP;
    }

    public static int widthToProgress(float width) {
        int p = Math.round((width - MIN_WIDTH) / STEP);
        return Math.max(0, Math.min(MAX_PROGRESS, p));
    }

    /** Display label for the current value, e.g. "0.5 pt" / "3 pt". */
    public static String label(float width) {
        float snapped = progressToWidth(widthToProgress(width));
        if (snapped == Math.floor(snapped)) {
            return String.format(Locale.US, "%d pt", (int) snapped);
        }
        return String.format(Locale.US, "%.1f pt", snapped);
    }
}
