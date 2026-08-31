package com.leslie.colorosnote.feature.editor;

import java.util.Locale;

/**
 * Maps a percentage SeekBar progress (0..100) to a 0..1 factor and back, for
 * the brush-properties numeric rows (pressure sensitivity, smoothing,
 * velocity, tilt). Pure JVM for unit tests.
 */
public final class PercentSliderMapper {

    public static final int MAX_PROGRESS = 100;

    private PercentSliderMapper() {
    }

    public static float progressToValue(int progress) {
        int p = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return p / (float) MAX_PROGRESS;
    }

    public static int valueToProgress(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        return Math.round(v * MAX_PROGRESS);
    }

    /** Display label for the current progress, e.g. "37 %". */
    public static String label(int progress) {
        int p = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return String.format(Locale.US, "%d %%", p);
    }
}
