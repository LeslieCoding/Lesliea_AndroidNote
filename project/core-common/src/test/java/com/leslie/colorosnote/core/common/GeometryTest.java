package com.leslie.colorosnote.core.common;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for the pure-math parts of {@link Geometry}. */
public class GeometryTest {

    private static final float EPS = 1e-4f;

    // ---- distancePointToSegment ----

    @Test
    public void distancePointToSegment_perpendicularHit() {
        // Point above the middle of a horizontal segment.
        assertEquals(5f, Geometry.distancePointToSegment(5f, 5f, 0f, 0f, 10f, 0f), EPS);
    }

    @Test
    public void distancePointToSegment_clampsToEndpoints() {
        // Projection falls beyond the segment end: distance to endpoint (10,0).
        assertEquals(5f, Geometry.distancePointToSegment(13f, 4f, 0f, 0f, 10f, 0f), EPS);
    }

    @Test
    public void distancePointToSegment_degenerateSegment() {
        assertEquals(10f, Geometry.distancePointToSegment(6f, 8f, 0f, 0f, 0f, 0f), EPS);
    }

    // ---- segmentsIntersect ----

    @Test
    public void segmentsIntersect_crossing() {
        assertTrue(Geometry.segmentsIntersect(0, 0, 10, 10, 0, 10, 10, 0));
    }

    @Test
    public void segmentsIntersect_parallelDisjoint() {
        assertFalse(Geometry.segmentsIntersect(0, 0, 10, 0, 0, 5, 10, 5));
    }

    @Test
    public void segmentsIntersect_endpointTouch() {
        assertTrue(Geometry.segmentsIntersect(0, 0, 5, 5, 5, 5, 10, 0));
    }

    @Test
    public void segmentsIntersect_collinearOverlap() {
        assertTrue(Geometry.segmentsIntersect(0, 0, 10, 0, 5, 0, 15, 0));
        assertFalse(Geometry.segmentsIntersect(0, 0, 4, 0, 5, 0, 15, 0));
    }

    @Test
    public void segmentIntersectionPoint_returnsExpectedPoint() {
        float[] p = Geometry.segmentIntersectionPoint(0, 0, 10, 10, 0, 10, 10, 0);
        assertNotNull(p);
        assertEquals(5f, p[0], EPS);
        assertEquals(5f, p[1], EPS);
    }

    @Test
    public void segmentIntersectionPoint_nullWhenDisjoint() {
        assertNull(Geometry.segmentIntersectionPoint(0, 0, 1, 1, 5, 5, 6, 4));
    }

    // ---- pointInPolygon ----

    @Test
    public void pointInPolygon_square() {
        float[] square = {0, 0, 10, 0, 10, 10, 0, 10};
        assertTrue(Geometry.pointInPolygon(square, 4, 5, 5));
        assertFalse(Geometry.pointInPolygon(square, 4, 15, 5));
        assertFalse(Geometry.pointInPolygon(square, 4, -1, 5));
    }

    @Test
    public void pointInPolygon_concave() {
        // L-shaped (concave) polygon.
        float[] lshape = {0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10};
        assertTrue(Geometry.pointInPolygon(lshape, 6, 2, 7));   // inside lower-left arm
        assertFalse(Geometry.pointInPolygon(lshape, 6, 8, 7));  // inside the notch
        assertTrue(Geometry.pointInPolygon(lshape, 6, 8, 2));   // inside upper arm
    }

    // ---- catmullRomToBezier ----

    @Test
    public void catmullRomToBezier_segmentCountAndEndpoints() {
        float[] pts = {0, 0, 1, 1, 2, 0, 3, 1, 4, 0}; // 5 points -> 2 interior spans
        List<float[]> segments = Geometry.catmullRomToBezier(pts);
        assertEquals(2, segments.size());
        // Each segment ends exactly on control point P2 of its span.
        assertEquals(2f, segments.get(0)[4], EPS);
        assertEquals(0f, segments.get(0)[5], EPS);
        assertEquals(3f, segments.get(1)[4], EPS);
        assertEquals(1f, segments.get(1)[5], EPS);
    }

    @Test
    public void catmullRomToBezier_straightLineYieldsCollinearControls() {
        // Collinear points: the Bezier control points must lie on the same line.
        float[] pts = {0, 0, 1, 0, 2, 0, 3, 0};
        List<float[]> segments = Geometry.catmullRomToBezier(pts);
        assertEquals(1, segments.size());
        float[] seg = segments.get(0);
        assertEquals(4f / 3f, seg[0], EPS); // c1x = 1 + (2-0)/6
        assertEquals(0f, seg[1], EPS);
        assertEquals(5f / 3f, seg[2], EPS); // c2x = 2 - (3-1)/6
        assertEquals(0f, seg[3], EPS);
        assertEquals(2f, seg[4], EPS);
        assertEquals(0f, seg[5], EPS);
    }

    @Test
    public void catmullRomToBezier_tooFewPointsReturnsEmpty() {
        assertTrue(Geometry.catmullRomToBezier(new float[]{0, 0, 1, 1}).isEmpty());
    }
}
