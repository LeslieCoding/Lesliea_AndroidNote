package com.leslie.colorosnote.core.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Tests for the five default {@link BrushPreset} factories and their ranges. */
public class BrushPresetTest {

    @Test
    public void defaults_containsFiveDistinctPresets() {
        List<BrushPreset> presets = BrushPreset.defaults();
        assertEquals(5, presets.size());
        for (int i = 0; i < presets.size(); i++) {
            for (int j = i + 1; j < presets.size(); j++) {
                assertNotEquals(presets.get(i).id, presets.get(j).id);
                assertNotEquals(presets.get(i).brushType, presets.get(j).brushType);
            }
        }
    }

    @Test
    public void defaults_haveSaneCommonRanges() {
        for (BrushPreset p : BrushPreset.defaults()) {
            assertTrue(p.name, p.minWidth > 0f);
            assertTrue(p.name, p.minWidth <= p.baseWidth);
            assertTrue(p.name, p.baseWidth <= p.maxWidth);
            assertTrue(p.name, p.opacity > 0f && p.opacity <= 1f);
            assertTrue(p.name, p.smoothing >= 0f && p.smoothing <= 1f);
            assertTrue(p.name, p.stabilization >= 0f && p.stabilization <= 1f);
            assertTrue(p.name, p.pressureToWidth >= 0f && p.pressureToWidth <= 1f);
            assertTrue(p.name, p.pressureToOpacity >= 0f && p.pressureToOpacity <= 1f);
            assertTrue(p.name, p.hasValidCustomCurve());
        }
    }

    @Test
    public void fountainPen_pressureRangeMatchesRecommendation() {
        BrushPreset p = BrushPreset.fountainPen();
        assertTrue(p.pressureEnabled);
        // v1.1.6 brush feel: low pressure ~0.4x base, high pressure up to 1.9x.
        assertEquals(0.4f, p.minWidth / p.baseWidth, 1e-4f);
        float highRatio = p.maxWidth / p.baseWidth;
        assertTrue(highRatio >= 1.6f && highRatio <= 2.0f);
    }

    @Test
    public void ballpoint_fixedWidthNoTaper() {
        BrushPreset p = BrushPreset.ballpoint();
        // Real ballpoints have a constant ink line: fixed width, no pressure
        // or velocity response, no start/end taper (GoodNotes definition).
        assertFalse(p.pressureEnabled);
        assertEquals(p.baseWidth, p.minWidth, 1e-6f);
        assertEquals(p.baseWidth, p.maxWidth, 1e-6f);
        assertEquals(0f, p.pressureToWidth, 1e-6f);
        assertEquals(0f, p.pressureToOpacity, 1e-6f);
        assertEquals(0f, p.velocityToWidth, 1e-6f);
        assertEquals(0f, p.taperStart, 1e-6f);
        assertEquals(0f, p.taperEnd, 1e-6f);
    }

    @Test
    public void pencilHb_fixedWidthPressureToOpacity() {
        BrushPreset p = BrushPreset.pencilHb();
        // Notability pencil model: constant width, pressure drives darkness.
        assertEquals(p.baseWidth, p.minWidth, 1e-6f);
        assertEquals(p.baseWidth, p.maxWidth, 1e-6f);
        assertEquals(0f, p.pressureToWidth, 1e-6f);
        assertEquals(0f, p.velocityToWidth, 1e-6f);
        assertEquals(0f, p.tiltToWidth, 1e-6f);
        assertEquals(0f, p.taperStart, 1e-6f);
        assertEquals(0f, p.taperEnd, 1e-6f);
        assertTrue(p.pressureEnabled);
        assertTrue(p.pressureToOpacity > 0f);
        assertTrue(p.tiltEnabled);
        assertTrue(p.tiltToOpacity > 0f);
        assertEquals("texture_pencil_grain", p.textureId);
    }

    @Test
    public void highlighter_translucentMultiply() {
        BrushPreset p = BrushPreset.highlighter();
        assertFalse(p.pressureEnabled);
        assertTrue(p.opacity < 1f);
        assertEquals(BrushPreset.BlendMode.MULTIPLY, p.blendMode);
        assertTrue(p.baseWidth >= 10f); // highlighters are wide
    }

    @Test
    public void customCurveValidation() {
        BrushPreset p = BrushPreset.fountainPen();
        p.pressureCurve = BrushPreset.PressureCurve.CUSTOM;
        assertFalse(p.hasValidCustomCurve()); // no control points yet
        for (int i = 0; i < BrushPreset.MIN_CUSTOM_CURVE_POINTS; i++) {
            p.customCurvePoints.add(i / 3f);
            p.customCurvePoints.add(i / 3f);
        }
        assertTrue(p.hasValidCustomCurve());
    }

    @Test
    public void defaults_v2_personalityTuning() {
        // v2.0.0 factory defaults (round 2, data from the v2.0.0 velocity/
        // smoothing retune). Existing installs keep their stored tuning via
        // BrushPresetStore migration; these values only seed fresh presets.
        assertEquals(0.5f, BrushPreset.fountainPen().velocityToWidth, 1e-6f);
        assertEquals(0.2f, BrushPreset.ballpoint().smoothing, 1e-6f);
        BrushPreset pencil = BrushPreset.pencilHb();
        assertEquals(0.45f, pencil.tiltToOpacity, 1e-6f);
        assertEquals(0.2f, pencil.smoothing, 1e-6f);
        assertEquals(0.15f, BrushPreset.highlighter().smoothing, 1e-6f);
        // Personalities that must NOT drift: ballpoint/highlighter stay
        // fixed-width with zero velocity response; pencil width stays fixed.
        assertEquals(0f, BrushPreset.ballpoint().velocityToWidth, 1e-6f);
        assertEquals(0f, BrushPreset.highlighter().velocityToWidth, 1e-6f);
        assertEquals(0f, pencil.pressureToWidth, 1e-6f);
    }
}
