package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Detection modes, velocity fallback and adaptive pressure calibration. */
public class PressureSourceTest {

    private static final float EPS = 1e-4f;

    private static StrokePoint sample(int toolType, float pressure) {
        StrokePoint p = new StrokePoint();
        p.toolType = toolType;
        p.pressure = pressure;
        return p;
    }

    @Test
    public void constantStylusPressure_locksInSimulated() {
        PressureSource src = new PressureSource();
        for (int i = 0; i < 20; i++) {
            src.onSample(sample(PressureSource.TOOL_TYPE_STYLUS, 0.5f));
        }
        assertEquals(PressureSource.Mode.SIMULATED, src.getMode());
        assertTrue(src.isSimulated());
    }

    @Test
    public void varyingStylusPressure_locksInReal() {
        PressureSource src = new PressureSource();
        for (int i = 0; i < 20; i++) {
            src.onSample(sample(PressureSource.TOOL_TYPE_STYLUS, 0.2f + 0.05f * (i % 5)));
        }
        assertEquals(PressureSource.Mode.REAL, src.getMode());
        assertFalse(src.isSimulated());
    }

    @Test
    public void fingerInput_neverReal() {
        PressureSource src = new PressureSource();
        for (int i = 0; i < 20; i++) {
            src.onSample(sample(PressureSource.TOOL_TYPE_FINGER, 0.2f + 0.1f * (i % 4)));
        }
        assertEquals(PressureSource.Mode.SIMULATED, src.getMode());
    }

    @Test
    public void simulatedPressure_fasterIsThinner() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.SIMULATED);
        float slow = src.resolvePressure(1f, 0f);
        float fast = src.resolvePressure(1f, 4000f);
        assertEquals(1f, slow, EPS);
        assertEquals(0.2f, fast, EPS);
        assertTrue(slow > fast);
    }

    @Test
    public void realPressure_expandsCompressedRange() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.REAL);
        // Train on a stylus whose normal writing peaks at 0.5 (enough samples
        // for the decaying peak to converge, ~3s of writing at stylus rates).
        for (int i = 0; i < 400; i++) {
            src.resolvePressure(0.5f, 0f);
        }
        assertTrue(src.getCalibrationPeak() <= 0.55f);
        // Peak pressure maps near the top, mid pressure near the middle: the
        // usable dynamic range is much wider than the raw 0..0.5 input.
        assertEquals(0.95f, src.resolvePressure(0.5f, 0f), 0.05f);
        float mid = src.resolvePressure(0.25f, 0f);
        assertTrue("mid pressure should land well above raw 0.25, got " + mid,
                mid > 0.4f && mid < 0.6f);
    }

    @Test
    public void realPressure_hardPressNeverClips() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.REAL);
        for (int i = 0; i < 200; i++) {
            src.resolvePressure(0.4f, 0f);
        }
        // A sudden full-force press immediately re-raises the peak.
        assertTrue(src.resolvePressure(1f, 0f) >= 0.95f - EPS);
    }

    @Test
    public void calibration_survivesStrokeReset() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.REAL);
        for (int i = 0; i < 200; i++) {
            src.resolvePressure(0.4f, 0f);
        }
        float peak = src.getCalibrationPeak();
        src.reset();
        assertEquals(peak, src.getCalibrationPeak(), EPS);
        src.resetCalibration();
        assertEquals(1f, src.getCalibrationPeak(), EPS);
    }

    @Test
    public void unavailableMode_passesRawThrough() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.UNAVAILABLE);
        assertEquals(0.7f, src.resolvePressure(0.7f, 0f), EPS);
    }

    @Test
    public void nonFiniteDriverValuesNeverPoisonStrokeGeometry() {
        PressureSource src = new PressureSource();
        src.forceMode(PressureSource.Mode.REAL);
        assertTrue(Float.isFinite(src.resolvePressure(Float.NaN, Float.NaN)));
        assertTrue(Float.isFinite(src.resolvePressure(Float.POSITIVE_INFINITY, 0f)));
    }
}
