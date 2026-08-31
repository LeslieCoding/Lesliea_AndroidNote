package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.model.BrushPreset;

/**
 * Maps the popup's numeric slider rows (v1.4.21) to BrushPreset fields.
 *
 * <ul>
 *   <li>压力灵敏度 (pressure sensitivity): the pencil's pressure response is
 *       darkness, so it edits {@code pressureToOpacity}; all other tools edit
 *       {@code pressureToWidth}. Setting a non-zero value enables pressure for
 *       tools that ship with it off (ballpoint/highlighter), and 0 turns it
 *       back off.</li>
 *   <li>Fixed-width spread: ballpoint/highlighter ship with
 *       min == max == base (v1.4.16 fixed-width semantics), which would make a
 *       width pressure response invisible. When pressure sensitivity is
 *       non-zero the width range fans out to base*(1±SPREAD*sensitivity);
 *       sensitivity 0 restores the fixed width. The pencil keeps its fixed
 *       width (its pressure response is opacity), and the fountain/calligraphy
 *       pens manage their own min/max via the width slider and style cards.</li>
 *   <li>倾斜感应 (tilt sensitivity): the pencil's tilt response is side-shading
 *       darkness ({@code tiltToOpacity}); other tools widen the mark
 *       ({@code tiltToWidth}). Non-zero enables tilt, 0 disables it.</li>
 * </ul>
 *
 * Pure JVM for unit tests.
 */
public final class BrushParamMapping {

    /** Half-range of the pressure spread on fixed-width brushes at 100%. */
    static final float FIXED_WIDTH_PRESSURE_SPREAD = 0.4f;

    private BrushParamMapping() {
    }

    public static float pressureSensitivity(BrushPreset p) {
        return p.brushType == BrushPreset.BrushType.PENCIL
                ? p.pressureToOpacity : p.pressureToWidth;
    }

    public static void setPressureSensitivity(BrushPreset p, float v) {
        v = clamp01(v);
        if (p.brushType == BrushPreset.BrushType.PENCIL) {
            p.pressureToOpacity = v;
        } else {
            p.pressureToWidth = v;
            p.pressureEnabled = v > 0f;
            applyFixedWidthSpread(p);
        }
    }

    /**
     * Re-derives min/max for fixed-width brushes from baseWidth and the
     * pressure sensitivity. Must be called after baseWidth changes (the width
     * slider) so an active pressure response survives width adjustments.
     * No-op for fountain/calligraphy (own min/max management) and pencil
     * (fixed width by design — its pressure response is opacity).
     */
    public static void applyFixedWidthSpread(BrushPreset p) {
        if (p.brushType != BrushPreset.BrushType.BALLPOINT
                && p.brushType != BrushPreset.BrushType.HIGHLIGHTER) {
            return;
        }
        float v = clamp01(p.pressureToWidth);
        if (v > 0f) {
            p.minWidth = p.baseWidth * (1f - FIXED_WIDTH_PRESSURE_SPREAD * v);
            p.maxWidth = p.baseWidth * (1f + FIXED_WIDTH_PRESSURE_SPREAD * v);
        } else {
            p.minWidth = p.baseWidth;
            p.maxWidth = p.baseWidth;
        }
    }

    public static float tiltSensitivity(BrushPreset p) {
        return p.brushType == BrushPreset.BrushType.PENCIL
                ? p.tiltToOpacity : p.tiltToWidth;
    }

    public static void setTiltSensitivity(BrushPreset p, float v) {
        v = clamp01(v);
        if (p.brushType == BrushPreset.BrushType.PENCIL) {
            p.tiltToOpacity = v;
        } else {
            p.tiltToWidth = v;
        }
        p.tiltEnabled = v > 0f;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
