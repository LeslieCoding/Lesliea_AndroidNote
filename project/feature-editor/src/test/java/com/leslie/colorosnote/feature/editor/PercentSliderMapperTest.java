package com.leslie.colorosnote.feature.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Percent slider mapping: 0..100% <-> 0..1. */
public class PercentSliderMapperTest {

    @Test
    public void endpointsMapExactly() {
        assertEquals(0f, PercentSliderMapper.progressToValue(0), 1e-6f);
        assertEquals(1f, PercentSliderMapper.progressToValue(PercentSliderMapper.MAX_PROGRESS), 1e-6f);
        assertEquals(0, PercentSliderMapper.valueToProgress(0f));
        assertEquals(PercentSliderMapper.MAX_PROGRESS, PercentSliderMapper.valueToProgress(1f));
    }

    @Test
    public void outOfRangeClamps() {
        assertEquals(0f, PercentSliderMapper.progressToValue(-5), 1e-6f);
        assertEquals(1f, PercentSliderMapper.progressToValue(250), 1e-6f);
        assertEquals(0, PercentSliderMapper.valueToProgress(-0.3f));
        assertEquals(PercentSliderMapper.MAX_PROGRESS, PercentSliderMapper.valueToProgress(1.7f));
    }

    @Test
    public void roundTripAcrossWholeRange() {
        for (int p = 0; p <= PercentSliderMapper.MAX_PROGRESS; p++) {
            float v = PercentSliderMapper.progressToValue(p);
            assertEquals("progress " + p, p, PercentSliderMapper.valueToProgress(v));
        }
    }

    @Test
    public void storedDefaultsLandOnExactPercents() {
        // Factory defaults like smoothing 0.2 / velocityToWidth 0.4 / tilt 0.3
        // must restore exactly onto the 1% grid.
        float[] defaults = {0f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.8f, 0.85f, 1f};
        for (float d : defaults) {
            int p = PercentSliderMapper.valueToProgress(d);
            assertEquals("value " + d, d, PercentSliderMapper.progressToValue(p), 1e-6f);
        }
    }

    @Test
    public void labelFormatsPercent() {
        assertEquals("0 %", PercentSliderMapper.label(0));
        assertEquals("37 %", PercentSliderMapper.label(37));
        assertEquals("100 %", PercentSliderMapper.label(100));
        assertEquals("100 %", PercentSliderMapper.label(140)); // clamped
    }
}
