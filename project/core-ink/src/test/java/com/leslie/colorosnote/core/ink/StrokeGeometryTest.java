package com.leslie.colorosnote.core.ink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for extreme-zoom bow-tie / missing-pixel geometry. */
public class StrokeGeometryTest {

    @Test
    public void segmentQuad_usesOneLocalNormalAndCannotBowTie() {
        float[] q = new float[8];
        assertTrue(StrokeGeometry.segmentQuad(0f, 0f, 40f,
                1f, 0f, 60f, q));

        // Both left points stay on one side; both right points on the other.
        // This remains true even when width dwarfs segment length — the exact
        // extreme-zoom case that crossed averaged-normal outline rails.
        assertTrue(q[1] > 0f);
        assertTrue(q[3] > 0f);
        assertTrue(q[5] < 0f);
        assertTrue(q[7] < 0f);
        assertEquals(0f, (q[0] + q[6]) * 0.5f, 1e-6f);
        assertEquals(1f, (q[2] + q[4]) * 0.5f, 1e-6f);
    }

    @Test
    public void segmentQuad_rejectsCoincidentAndInvalidInputs() {
        float[] q = new float[8];
        assertFalse(StrokeGeometry.segmentQuad(2f, 3f, 10f,
                2f, 3f, 10f, q));
        assertFalse(StrokeGeometry.segmentQuad(Float.NaN, 0f, 10f,
                1f, 0f, 10f, q));
    }

    @Test
    public void segmentQuad_keepsOneWindingForEveryTravelDirection() {
        float[][] ends = {{9f, 2f}, {-9f, 2f}, {2f, 9f}, {2f, -9f}};
        for (float[] end : ends) {
            float[] q = new float[8];
            assertTrue(StrokeGeometry.segmentQuad(0f, 0f, 40f,
                    end[0], end[1], 70f, q));
            float twiceArea = 0f;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                twiceArea += q[i * 2] * q[j * 2 + 1]
                        - q[j * 2] * q[i * 2 + 1];
            }
            assertTrue("winding must stay negative for " + end[0] + "," + end[1],
                    twiceArea < 0f);
        }
    }

    @Test
    public void gentleTurnUsesConsistentlyWoundTriangle() {
        float[] t = new float[6];
        double a = Math.toRadians(20);
        int kind = StrokeGeometry.joinTriangle(1f, 0f,
                (float) Math.cos(a), (float) Math.sin(a),
                10f, 20f, 8f, t);
        assertEquals(StrokeGeometry.JOIN_TRIANGLE, kind);
        float twiceArea = t[0] * t[3] + t[2] * t[5] + t[4] * t[1]
                - t[2] * t[1] - t[4] * t[3] - t[0] * t[5];
        assertTrue(twiceArea < 0f);
    }

    @Test
    public void straightNeedsNoJoinAndSharpTurnKeepsRoundFallback() {
        float[] t = new float[6];
        assertEquals(StrokeGeometry.JOIN_NONE,
                StrokeGeometry.joinTriangle(1f, 0f, 2f, 0f,
                        0f, 0f, 10f, t));
        assertEquals(StrokeGeometry.JOIN_ROUND,
                StrokeGeometry.joinTriangle(1f, 0f, -0.5f, 0.866f,
                        0f, 0f, 10f, t));
    }
}
