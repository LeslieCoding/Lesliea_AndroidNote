package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Highlighter "straighten" (拉直) transform. */
public class StraightenTest {

    private static List<StrokePoint> line(float fromX, float toX, float y, int count) {
        List<StrokePoint> pts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float x = fromX + (toX - fromX) * i / (count - 1);
            pts.add(new StrokePoint(x, y + jitter(i), 0L, 0.5f));
        }
        return pts;
    }

    private static float jitter(int i) {
        // Small hand-wobble: within a few percent of a 100-unit stroke.
        return (i % 5 == 0 ? 1.2f : -0.8f);
    }

    private static float maxPerpDeviation(List<StrokePoint> pts) {
        StrokePoint a = pts.get(0);
        StrokePoint b = pts.get(pts.size() - 1);
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float len = (float) Math.hypot(dx, dy);
        float ux = dx / len;
        float uy = dy / len;
        float max = 0f;
        for (StrokePoint p : pts) {
            max = Math.max(max, Math.abs((p.x - a.x) * uy - (p.y - a.y) * ux));
        }
        return max;
    }

    @Test
    public void roughlyStraightLine_becomesPerfectlyStraight() {
        List<StrokePoint> pts = line(0f, 100f, 50f, 20);
        assertTrue("jittered highlight should be straightened",
                Straighten.apply(pts));
        assertEquals(0f, maxPerpDeviation(pts), 1e-4f);
    }

    @Test
    public void curvedStroke_isLeftUntouched() {
        // A semicircle deviates far beyond 6% of its chord.
        List<StrokePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            double a = Math.PI * i / 12.0;
            pts.add(new StrokePoint((float) Math.cos(a) * 50f,
                    (float) Math.sin(a) * 50f + 50f, 0L, 0.5f));
        }
        List<StrokePoint> before = copy(pts);
        assertFalse("a curve must not be straightened", Straighten.apply(pts));
        for (int i = 0; i < pts.size(); i++) {
            assertEquals(before.get(i).x, pts.get(i).x, 1e-5f);
            assertEquals(before.get(i).y, pts.get(i).y, 1e-5f);
        }
    }

    @Test
    public void endpointsStayFixed() {
        List<StrokePoint> pts = line(10f, 90f, 40f, 15);
        float firstX = pts.get(0).x, firstY = pts.get(0).y;
        float lastX = pts.get(pts.size() - 1).x, lastY = pts.get(pts.size() - 1).y;
        Straighten.apply(pts);
        assertEquals(firstX, pts.get(0).x, 1e-5f);
        assertEquals(firstY, pts.get(0).y, 1e-5f);
        assertEquals(lastX, pts.get(pts.size() - 1).x, 1e-5f);
        assertEquals(lastY, pts.get(pts.size() - 1).y, 1e-5f);
    }

    @Test
    public void degenerateStrokesAreSkipped() {
        assertFalse(Straighten.apply(new ArrayList<>()));
        List<StrokePoint> single = new ArrayList<>();
        single.add(new StrokePoint(1f, 1f, 0L, 0.5f));
        assertFalse(Straighten.apply(single));
        List<StrokePoint> dot = new ArrayList<>();
        dot.add(new StrokePoint(1f, 1f, 0L, 0.5f));
        dot.add(new StrokePoint(1f, 1f, 1L, 0.5f));
        dot.add(new StrokePoint(1f, 1f, 2L, 0.5f));
        assertFalse(Straighten.apply(dot));
    }

    private static List<StrokePoint> copy(List<StrokePoint> src) {
        List<StrokePoint> out = new ArrayList<>(src.size());
        for (StrokePoint p : src) {
            out.add(new StrokePoint(p.x, p.y, p.timestampMs, p.pressure));
        }
        return out;
    }
}
