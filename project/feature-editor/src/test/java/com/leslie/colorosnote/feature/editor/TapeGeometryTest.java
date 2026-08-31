package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Pure-JVM tests for tape geometry (spec 19). */
public class TapeGeometryTest {

    @Test
    public void simplifyKeepsEndpointsAndDropsCollinearPoints() {
        List<Float> line = Arrays.asList(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f);
        List<Float> out = TapeGeometry.simplify(line, 1f);
        assertEquals(Arrays.asList(0f, 0f, 30f, 0f), out);
    }

    @Test
    public void simplifyKeepsSharpTurn() {
        List<Float> zigzag = Arrays.asList(0f, 0f, 10f, 0f, 10f, 20f, 20f, 20f);
        List<Float> out = TapeGeometry.simplify(zigzag, 1f);
        // The corner at (10,0) and (10,20) must survive.
        assertEquals(4, out.size() / 2);
        assertEquals(10f, out.get(2), 0.001f);
        assertEquals(0f, out.get(3), 0.001f);
    }

    @Test
    public void simplifyShortPathsPassThrough() {
        List<Float> single = Arrays.asList(5f, 6f);
        assertEquals(single, TapeGeometry.simplify(single, 1f));
    }

    @Test
    public void pathBoundsIncludesHalfWidth() {
        List<Float> path = Arrays.asList(10f, 20f, 110f, 20f);
        float[] b = TapeGeometry.pathBounds(path, 40f);
        assertEquals(-10f, b[0], 0.001f);
        assertEquals(0f, b[1], 0.001f);
        assertEquals(130f, b[2], 0.001f);
        assertEquals(40f, b[3], 0.001f);
    }

    @Test
    public void straightRectExpandsThinDimensionToWidth() {
        // Nearly horizontal drag: height becomes the tape width.
        float[] r = TapeGeometry.straightRect(0f, 50f, 200f, 60f, 40f);
        assertEquals(0f, r[0], 0.001f);
        assertEquals(200f, r[2], 0.001f);
        assertEquals(40f, r[3] - r[1], 0.001f);
        assertEquals(55f, (r[1] + r[3]) / 2f, 0.001f); // centered on the drag

        // Nearly vertical drag: width becomes the tape width.
        float[] v = TapeGeometry.straightRect(100f, 0f, 105f, 300f, 40f);
        assertEquals(40f, v[2] - v[0], 0.001f);
        assertEquals(300f, v[3] - v[1], 0.001f);
    }

    @Test
    public void distanceToPathMatchesSegmentDistance() {
        List<Float> path = Arrays.asList(0f, 0f, 100f, 0f);
        assertEquals(10f, TapeGeometry.distanceToPath(path, 50f, 10f), 0.001f);
        assertEquals(10f, TapeGeometry.distanceToPath(path, 50f, -10f), 0.001f);
        // Beyond the end cap: distance to the endpoint.
        assertEquals(20f, TapeGeometry.distanceToPath(path, 120f, 0f), 0.001f);
        assertTrue(TapeGeometry.distanceToPath(path, 50f, 0f) < 0.001f);
    }
}
