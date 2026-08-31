package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.model.BrushPreset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Popup numeric-row -> BrushPreset field mapping (v1.4.21). */
public class BrushParamMappingTest {

    @Test
    public void pencilPressureSensitivityEditsOpacityChannel() {
        BrushPreset p = BrushPreset.pencilHb();
        assertEquals(0.8f, BrushParamMapping.pressureSensitivity(p), 1e-6f);
        BrushParamMapping.setPressureSensitivity(p, 0.5f);
        assertEquals(0.5f, p.pressureToOpacity, 1e-6f);
        assertEquals(0f, p.pressureToWidth, 1e-6f); // width stays untouched
    }

    @Test
    public void fountainPressureSensitivityEditsWidthChannel() {
        BrushPreset p = BrushPreset.fountainPen();
        assertEquals(1f, BrushParamMapping.pressureSensitivity(p), 1e-6f);
        BrushParamMapping.setPressureSensitivity(p, 0.3f);
        assertEquals(0.3f, p.pressureToWidth, 1e-6f);
        assertTrue(p.pressureEnabled);
    }

    @Test
    public void zeroPressureSensitivityDisablesPressureForNonPencil() {
        BrushPreset p = BrushPreset.fountainPen();
        BrushParamMapping.setPressureSensitivity(p, 0f);
        assertEquals(0f, p.pressureToWidth, 1e-6f);
        assertFalse(p.pressureEnabled);
    }

    @Test
    public void ballpointPressureSpreadsFixedWidthAndRestores() {
        BrushPreset p = BrushPreset.ballpoint();
        assertEquals(p.baseWidth, p.minWidth, 1e-6f);
        assertEquals(p.baseWidth, p.maxWidth, 1e-6f);
        assertFalse(p.pressureEnabled);

        BrushParamMapping.setPressureSensitivity(p, 1f);
        assertTrue(p.pressureEnabled);
        assertEquals(p.baseWidth * 0.6f, p.minWidth, 1e-5f);
        assertEquals(p.baseWidth * 1.4f, p.maxWidth, 1e-5f);

        BrushParamMapping.setPressureSensitivity(p, 0f);
        assertEquals(p.baseWidth, p.minWidth, 1e-6f);
        assertEquals(p.baseWidth, p.maxWidth, 1e-6f);
        assertFalse(p.pressureEnabled);
    }

    @Test
    public void fixedWidthSpreadFollowsBaseWidthChanges() {
        BrushPreset p = BrushPreset.highlighter();
        BrushParamMapping.setPressureSensitivity(p, 0.5f);
        p.baseWidth = 20f;
        BrushParamMapping.applyFixedWidthSpread(p);
        assertEquals(20f * (1f - 0.4f * 0.5f), p.minWidth, 1e-5f);
        assertEquals(20f * (1f + 0.4f * 0.5f), p.maxWidth, 1e-5f);
    }

    @Test
    public void fixedWidthSpreadLeavesPencilAndFountainAlone() {
        BrushPreset pencil = BrushPreset.pencilHb();
        pencil.pressureToWidth = 1f; // hypothetical; pencil pressure is opacity
        BrushParamMapping.applyFixedWidthSpread(pencil);
        assertEquals(pencil.baseWidth, pencil.minWidth, 1e-6f);
        assertEquals(pencil.baseWidth, pencil.maxWidth, 1e-6f);

        BrushPreset pen = BrushPreset.fountainPen();
        float min = pen.minWidth, max = pen.maxWidth;
        pen.pressureToWidth = 0.8f;
        BrushParamMapping.applyFixedWidthSpread(pen);
        assertEquals(min, pen.minWidth, 1e-6f);
        assertEquals(max, pen.maxWidth, 1e-6f);
    }

    @Test
    public void pencilTiltSensitivityEditsOpacityChannel() {
        BrushPreset p = BrushPreset.pencilHb();
        // v2.0.0: pencil factory default tiltToOpacity 0.3 -> 0.45.
        assertEquals(0.45f, BrushParamMapping.tiltSensitivity(p), 1e-6f);
        BrushParamMapping.setTiltSensitivity(p, 0.6f);
        assertEquals(0.6f, p.tiltToOpacity, 1e-6f);
        assertEquals(0f, p.tiltToWidth, 1e-6f);
        assertTrue(p.tiltEnabled);
    }

    @Test
    public void tiltSensitivityTogglesTiltEnabled() {
        BrushPreset p = BrushPreset.ballpoint();
        assertFalse(p.tiltEnabled);
        BrushParamMapping.setTiltSensitivity(p, 0.5f);
        assertTrue(p.tiltEnabled);
        assertEquals(0.5f, p.tiltToWidth, 1e-6f);
        BrushParamMapping.setTiltSensitivity(p, 0f);
        assertFalse(p.tiltEnabled);
        assertEquals(0f, p.tiltToWidth, 1e-6f);
    }

    @Test
    public void settersClampToUnitRange() {
        BrushPreset p = BrushPreset.fountainPen();
        BrushParamMapping.setPressureSensitivity(p, 1.7f);
        assertEquals(1f, p.pressureToWidth, 1e-6f);
        BrushParamMapping.setTiltSensitivity(p, -0.2f);
        assertEquals(0f, p.tiltToWidth, 1e-6f);
        assertFalse(p.tiltEnabled);
    }
}
