package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Built-in curve monotonicity/range + custom curve interpolation. */
public class PressureCurveTest {

    private static final float EPS = 1e-5f;

    @Test
    public void builtinCurves_monotonicAndRanged() {
        for (BrushPreset.PressureCurve type : new BrushPreset.PressureCurve[]{
                BrushPreset.PressureCurve.SOFT,
                BrushPreset.PressureCurve.BALANCED,
                BrushPreset.PressureCurve.FIRM}) {
            float prev = -1f;
            for (int i = 0; i <= 100; i++) {
                float p = i / 100f;
                float v = PressureCurve.eval(type, p);
                assertTrue("out of range for " + type + " at " + p,
                        v >= 0f && v <= 1f + EPS);
                assertTrue("not monotonic for " + type + " at " + p,
                        v >= prev - EPS);
                prev = v;
            }
            // Light pressure still produces output; full pressure saturates.
            assertTrue(PressureCurve.eval(type, 0f) > 0f);
            assertEquals(1f, PressureCurve.eval(type, 1f), 1e-3f);
        }
    }

    @Test
    public void builtinCurves_orderingAtLightPressure() {
        // SOFT responds earliest, FIRM latest.
        float p = 0.3f;
        float soft = PressureCurve.eval(BrushPreset.PressureCurve.SOFT, p);
        float balanced = PressureCurve.eval(BrushPreset.PressureCurve.BALANCED, p);
        float firm = PressureCurve.eval(BrushPreset.PressureCurve.FIRM, p);
        assertTrue(soft > balanced);
        assertTrue(balanced > firm);
    }

    @Test
    public void customCurve_monotonicInterpolation() {
        List<Float> points = Arrays.asList(
                0f, 0.1f,
                0.3f, 0.4f,
                0.6f, 0.7f,
                1f, 1f);
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float v = PressureCurve.evalCustom(points, i / 100f);
            assertTrue(v >= prev - EPS);
            prev = v;
        }
        // Exact hits at control points.
        assertEquals(0.1f, PressureCurve.evalCustom(points, 0f), EPS);
        assertEquals(0.4f, PressureCurve.evalCustom(points, 0.3f), EPS);
        assertEquals(1f, PressureCurve.evalCustom(points, 1f), EPS);
        // Clamping outside the x range.
        assertEquals(0.1f, PressureCurve.evalCustom(points, -1f), EPS);
    }

    @Test
    public void customCurve_nonMonotoneInputIsClamped() {
        List<Float> points = Arrays.asList(
                0f, 0.8f,
                0.3f, 0.2f, // dips down; must be clamped to stay monotonic
                0.6f, 0.5f,
                1f, 1f);
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float v = PressureCurve.evalCustom(points, i / 100f);
            assertTrue(v >= prev - EPS);
            prev = v;
        }
    }

    @Test
    public void customCurve_invalidFallsBackToBalanced() {
        List<Float> tooFew = Arrays.asList(0f, 0f, 0.5f, 0.5f, 1f, 1f);
        for (int i = 0; i <= 10; i++) {
            float p = i / 10f;
            assertEquals(PressureCurve.eval(BrushPreset.PressureCurve.BALANCED, p),
                    PressureCurve.evalCustom(tooFew, p), EPS);
        }
    }

    @Test
    public void softCurve_v2_lightPressureFinerWithAntiDropoutFloor() {
        // v2.0.0: SOFT exponent 0.55 -> 0.75 (industry gamma 0.7-0.8 band) and
        // the output floor 0.05 -> 0.12. Light strokes stay finer than
        // v1.4.21 (more light-pressure finesse) while a feather touch still
        // inks (no dropout).
        float soft03 = PressureCurve.eval(BrushPreset.PressureCurve.SOFT, 0.3f);
        float v1421At03 = 0.05f + 0.95f * (float) Math.pow(0.3f, 0.55f);
        assertEquals(0.12f + 0.88f * (float) Math.pow(0.3f, 0.75f), soft03, 1e-5f);
        assertTrue("light pressure must be finer than v1.4.21",
                soft03 < v1421At03 - 0.05f);
        assertEquals("anti-dropout floor", 0.12f,
                PressureCurve.eval(BrushPreset.PressureCurve.SOFT, 0f), 1e-5f);
        assertEquals(1f, PressureCurve.eval(BrushPreset.PressureCurve.SOFT, 1f), 1e-3f);
        // Mid range still clearly separated from the light end.
        assertTrue(PressureCurve.eval(BrushPreset.PressureCurve.SOFT, 0.6f)
                > soft03 + 0.2f);
    }
}
