package com.leslie.colorosnote.core.ink;

import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The prediction strip must stay on the curve the pen is drawing.
 *
 * <p>Reported symptom: "I draw a circle quickly and it shows up as a polygon."
 * The strip is painted every frame at the pen tip and extends several page
 * units past the last real sample; extrapolating it along the straight tangent
 * therefore stamps a visible straight chord onto every frame of a curve, and
 * the sequence of those chords reads as a polygon. Nothing is wrong with the
 * committed stroke — this is purely what the user sees while writing, which is
 * exactly the part they complained about.
 */
public class PredictionCurveFidelityTest {

    private static final float RADIUS = 30f;
    private static final float CENTRE_X = 100f;
    private static final float CENTRE_Y = 100f;

    @Test
    public void extensionOnACircleStaysOnTheCircle() {
        List<StrokePoint> predicted = arc(6, 900f);
        float lastReal = predicted.get(predicted.size() - 1).timestampMs;
        PredictionExtender.extend(predicted, (long) lastReal);

        float worst = 0f;
        for (StrokePoint p : predicted) {
            float r = (float) Math.hypot(p.x - CENTRE_X, p.y - CENTRE_Y);
            worst = Math.max(worst, Math.abs(r - RADIUS));
        }
        // A straight tangent extrapolation bows off the circle by several page
        // units at this speed; following the arc keeps it within a fraction of
        // the stroke width, where it is invisible.
        assertTrue("prediction left the circle by " + worst + " page units",
                worst < 1.0f);
    }

    @Test
    public void extensionStillReachesForwardOnACircle() {
        List<StrokePoint> predicted = arc(6, 900f);
        int before = predicted.size();
        PredictionExtender.extend(predicted,
                predicted.get(before - 1).timestampMs);
        assertTrue("prediction must still extend, got "
                + (predicted.size() - before) + " new points",
                predicted.size() > before);
    }

    @Test
    public void straightStrokeExtrapolationIsUnchanged() {
        List<StrokePoint> predicted = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            predicted.add(at(10f + i * 4f, 50f, 1000L + i * 4L));
        }
        PredictionExtender.extend(predicted, 1012L);
        // Still a straight line along +x.
        for (StrokePoint p : predicted) {
            assertTrue("straight stroke bent to y=" + p.y,
                    Math.abs(p.y - 50f) < 1e-3f);
        }
        assertTrue(predicted.size() > 4);
    }

    @Test
    public void aHardCornerDoesNotGetASpiral() {
        // Two legs meeting at a right angle: the turn estimate is huge, so the
        // horizon must shrink rather than swing the strip around the corner.
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(at(10f, 50f, 1000L));
        predicted.add(at(20f, 50f, 1004L));
        predicted.add(at(20f, 60f, 1008L));
        int before = predicted.size();
        PredictionExtender.extend(predicted, 1008L);
        // Only the appended points are the prediction; the three inputs above
        // are the pen's real path and are naturally far from the corner.
        for (int i = before; i < predicted.size(); i++) {
            StrokePoint p = predicted.get(i);
            float d = (float) Math.hypot(p.x - 20f, p.y - 60f);
            assertTrue("corner prediction ran away by " + d, d < 12f);
        }
    }

    @Test
    public void nearStationaryPenIsNotExtrapolated() {
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(at(10f, 50f, 1000L));
        predicted.add(at(10.01f, 50f, 1004L));
        int before = predicted.size();
        PredictionExtender.extend(predicted, 1004L);
        assertTrue(predicted.size() == before);
    }

    // ------------------------------------------------------------------

    /** Points along the circle at the given speed in page units/second. */
    private static List<StrokePoint> arc(int count, float speedUnitsPerSec) {
        List<StrokePoint> pts = new ArrayList<>(count);
        float stepMs = 4f;
        float arcPerStep = speedUnitsPerSec * (stepMs / 1000f);
        float dTheta = arcPerStep / RADIUS;
        for (int i = 0; i < count; i++) {
            float theta = i * dTheta;
            pts.add(at(CENTRE_X + RADIUS * (float) Math.cos(theta),
                    CENTRE_Y + RADIUS * (float) Math.sin(theta),
                    1000L + (long) (i * stepMs)));
        }
        return pts;
    }

    private static StrokePoint at(float x, float y, long t) {
        StrokePoint p = new StrokePoint();
        p.x = x;
        p.y = y;
        p.timestampMs = t;
        p.pressure = 0.5f;
        return p;
    }
}
