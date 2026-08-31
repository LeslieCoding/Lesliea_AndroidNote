package com.leslie.colorosnote.core.ink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Velocity estimation from timestamped samples. */
public class VelocityTrackerTest {

    @Test
    public void constantMotion_estimatesSpeed() {
        VelocityTracker tracker = new VelocityTracker();
        // Moving 1000 units/s along +x.
        for (int i = 0; i < 10; i++) {
            tracker.addSample(i * 10f, 0f, i * 10L);
        }
        // EMA converges asymptotically; allow 0.5% residual.
        assertEquals(1000f, tracker.getSpeed(), 5f);
        assertEquals(1000f, tracker.getVelocityX(), 5f);
        assertEquals(0f, tracker.getVelocityY(), 5f);
    }

    @Test
    public void reset_clearsState() {
        VelocityTracker tracker = new VelocityTracker();
        tracker.addSample(0f, 0f, 0L);
        tracker.addSample(100f, 0f, 10L);
        tracker.reset();
        assertEquals(0f, tracker.getSpeed(), 1e-6f);
    }
}
