package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Prediction-strip extension horizon, caps and guards. */
public class PredictionExtenderTest {

    private static StrokePoint point(float x, float y, long t) {
        StrokePoint p = new StrokePoint();
        p.x = x;
        p.y = y;
        p.timestampMs = t;
        p.pressure = 0.5f;
        return p;
    }

    @Test
    public void extendsToTargetHorizon() {
        // Library prediction reaches +12ms at 500 units/s along +x.
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(point(0f, 0f, 104));
        predicted.add(point(2f, 0f, 108));
        predicted.add(point(4f, 0f, 112));
        PredictionExtender.extend(predicted, 100);
        StrokePoint last = predicted.get(predicted.size() - 1);
        assertTrue("must reach the 25ms horizon",
                last.timestampMs >= 100 + PredictionExtender.TARGET_HORIZON_MS);
        assertTrue("extension stays collinear", last.y == 0f);
        assertTrue("moves forward", last.x > 4f);
        assertEquals(0.5f, last.pressure, 1e-5f);
    }

    @Test
    public void respectsDistanceCap() {
        // 5000 units/s: 25ms horizon would mean 125 units — cap kicks in.
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(point(0f, 0f, 104));
        predicted.add(point(20f, 0f, 108));
        predicted.add(point(40f, 0f, 112));
        PredictionExtender.extend(predicted, 100);
        StrokePoint last = predicted.get(predicted.size() - 1);
        assertTrue("extension capped at " + PredictionExtender.MAX_EXTENSION_UNITS,
                last.x - 40f <= PredictionExtender.MAX_EXTENSION_UNITS + 1e-3f);
    }

    @Test
    public void skipsWhenSlow() {
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(point(0f, 0f, 104));
        predicted.add(point(0.05f, 0f, 108)); // ~12 units/s
        PredictionExtender.extend(predicted, 100);
        assertEquals(2, predicted.size());
    }

    @Test
    public void noOpWhenHorizonAlreadyCovered() {
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(point(0f, 0f, 116));
        predicted.add(point(4f, 0f, 120));
        predicted.add(point(8f, 0f, 130)); // beyond 100 + 25
        PredictionExtender.extend(predicted, 100);
        assertEquals(3, predicted.size());
    }

    @Test
    public void noOpWithTooFewPoints() {
        List<StrokePoint> predicted = new ArrayList<>();
        predicted.add(point(0f, 0f, 100));
        PredictionExtender.extend(predicted, 100);
        assertEquals(1, predicted.size());
        PredictionExtender.extend(new ArrayList<>(), 100); // must not throw
    }
}
