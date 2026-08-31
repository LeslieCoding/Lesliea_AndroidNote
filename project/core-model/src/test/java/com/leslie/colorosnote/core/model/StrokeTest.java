package com.leslie.colorosnote.core.model;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link Stroke} bounds and transform. Uses the pure-JVM variants
 * ({@link Stroke#computeBounds()} / {@link Stroke#transformValues(float[])})
 * because android.graphics classes are stubs in local unit tests.
 */
public class StrokeTest {

    private static Stroke strokeOf(float... coords) {
        Stroke s = new Stroke();
        for (int i = 0; i + 1 < coords.length; i += 2) {
            s.points.add(new StrokePoint(coords[i], coords[i + 1], i, 0.5f));
        }
        return s;
    }

    @Test
    public void bounds_ofSimpleStroke() {
        Stroke s = strokeOf(10, 20, 50, 5, 30, 40);
        assertArrayEquals(new float[]{10f, 5f, 50f, 40f}, s.computeBounds(), 0f);
    }

    @Test
    public void bounds_emptyStrokeIsZero() {
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, strokeOf().computeBounds(), 0f);
    }

    @Test
    public void transform_translate() {
        Stroke s = strokeOf(0, 0, 10, 10);
        // Identity matrix + translation (10, 5).
        s.transformValues(new float[]{1, 0, 10, 0, 1, 5, 0, 0, 1});
        assertEquals(10f, s.points.get(0).x, 1e-5f);
        assertEquals(5f, s.points.get(0).y, 1e-5f);
        assertEquals(20f, s.points.get(1).x, 1e-5f);
        assertEquals(15f, s.points.get(1).y, 1e-5f);
    }

    @Test
    public void transform_scale() {
        Stroke s = strokeOf(1, 2, 3, 4);
        s.transformValues(new float[]{2, 0, 0, 0, 3, 0, 0, 0, 1});
        assertEquals(2f, s.points.get(0).x, 1e-5f);
        assertEquals(6f, s.points.get(0).y, 1e-5f);
        assertEquals(6f, s.points.get(1).x, 1e-5f);
        assertEquals(12f, s.points.get(1).y, 1e-5f);
        assertArrayEquals(new float[]{2f, 6f, 6f, 12f}, s.computeBounds(), 1e-5f);
    }

    @Test
    public void transform_rotate90Degrees() {
        Stroke s = strokeOf(1, 0);
        // cos(90)=0, sin(90)=1 -> x' = -y, y' = x
        s.transformValues(new float[]{0, -1, 0, 1, 0, 0, 0, 0, 1});
        assertEquals(0f, s.points.get(0).x, 1e-5f);
        assertEquals(1f, s.points.get(0).y, 1e-5f);
    }
}
