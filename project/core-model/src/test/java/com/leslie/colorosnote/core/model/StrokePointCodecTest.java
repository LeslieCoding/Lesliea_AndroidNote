package com.leslie.colorosnote.core.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Round-trip tests for the compact binary codec of {@link StrokePoint}. */
public class StrokePointCodecTest {

    private static StrokePoint sample(float seed) {
        StrokePoint p = new StrokePoint();
        p.x = 100.5f + seed;
        p.y = 200.25f + seed;
        p.timestampMs = 1_700_000_000_000L + (long) seed;
        p.pressure = 0.75f;
        p.tilt = 0.3f;
        p.orientation = 1.1f;
        p.toolType = 2; // TOOL_TYPE_STYLUS
        p.pointerId = 7;
        p.velocityX = -12.5f;
        p.velocityY = 34.75f;
        return p;
    }

    private static void assertSamePoint(StrokePoint expected, StrokePoint actual) {
        assertEquals(expected.x, actual.x, 0f);
        assertEquals(expected.y, actual.y, 0f);
        assertEquals(expected.timestampMs, actual.timestampMs);
        assertEquals(expected.pressure, actual.pressure, 0f);
        assertEquals(expected.tilt, actual.tilt, 0f);
        assertEquals(expected.orientation, actual.orientation, 0f);
        assertEquals(expected.toolType, actual.toolType);
        assertEquals(expected.pointerId, actual.pointerId);
        assertEquals(expected.velocityX, actual.velocityX, 0f);
        assertEquals(expected.velocityY, actual.velocityY, 0f);
    }

    @Test
    public void listRoundTrip() {
        List<StrokePoint> points = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            points.add(sample(i));
        }
        byte[] blob = StrokePoint.encodeList(points);
        // Version byte header.
        assertEquals(StrokePoint.BINARY_VERSION, blob[0]);
        List<StrokePoint> decoded = StrokePoint.decodeList(blob);
        assertEquals(points.size(), decoded.size());
        for (int i = 0; i < points.size(); i++) {
            assertSamePoint(points.get(i), decoded.get(i));
        }
    }

    @Test
    public void emptyListRoundTrip() {
        List<StrokePoint> decoded = StrokePoint.decodeList(StrokePoint.encodeList(new ArrayList<>()));
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void singlePointRoundTrip() {
        StrokePoint p = sample(1f);
        byte[] blob = p.encodeToBytes();
        assertEquals(StrokePoint.BINARY_VERSION, blob[0]);
        assertSamePoint(p, StrokePoint.decodeFromBytes(blob));
    }

    @Test
    public void rejectsUnsupportedVersion() {
        byte[] blob = StrokePoint.encodeList(new ArrayList<>());
        blob[0] = 99;
        try {
            StrokePoint.decodeList(blob);
            fail("expected IllegalArgumentException for unknown version");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("version"));
        }
    }
}
