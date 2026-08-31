package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Raw pass-through, EMA convergence and pull-string stabilization. */
public class StrokeSmootherTest {

    private static BrushPreset preset(float smoothing, float stabilization) {
        BrushPreset p = new BrushPreset();
        p.smoothing = smoothing;
        p.stabilization = stabilization;
        return p;
    }

    @Test
    public void rawMode_passesThrough() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0f, 0f));
        s.reset();
        float[] out = s.process(10f, 20f, 0.5f, null);
        assertEquals(10f, out[0], 1e-5f);
        assertEquals(20f, out[1], 1e-5f);
        out = s.process(30f, 40f, 0.8f, null);
        assertEquals(30f, out[0], 1e-5f);
        assertEquals(0.8f, out[2], 1e-5f);
    }

    @Test
    public void smoothing_convergesToTarget() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.6f, 0f)); // alpha = 1 - 0.8*0.6 = 0.52
        s.reset();
        s.process(0f, 0f, 0.5f, null);
        float[] out = null;
        for (int i = 0; i < 40; i++) {
            out = s.process(100f, 0f, 1f, null);
        }
        assertEquals(100f, out[0], 0.5f);
        assertEquals(1f, out[2], 0.01f);
    }

    @Test
    public void stabilization_holdsUntilRadiusExceeded() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0f, 1f)); // radius = 24 page units
        s.reset();
        float[] first = s.process(0f, 0f, 1f, null);
        assertEquals(0f, first[0], 1e-5f);
        // Inside the radius: output stays on the anchor.
        float[] out = s.process(10f, 0f, 1f, null);
        assertEquals(0f, out[0], 1e-5f);
        // Outside the radius: output trails by exactly the radius.
        out = s.process(40f, 0f, 1f, null);
        assertEquals(16f, out[0], 0.5f);
    }

    @Test
    public void stabilizationOff_byDefault() {
        BrushPreset p = BrushPreset.fountainPen();
        p.stabilization = 0f;
        StrokeSmoother s = new StrokeSmoother();
        s.configure(p);
        s.reset();
        s.process(0f, 0f, 1f, null);
        float[] out = s.process(10f, 0f, 1f, null);
        assertTrue("must follow the pen, never rubber-band", out[0] > 0f);
    }

    /**
     * v1.4.11: on a fast constant-velocity ramp the 1€ filter's steady-state
     * lag must stay within a few page units (≈1/(2π·POS_BETA)); the old beta
     * trailed ~10 units behind the pen.
     */
    @Test
    public void highSpeed_lagStaysSmall() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.2f, 0f)); // fountain-pen default smoothing
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4; // ~240Hz stylus stream
        float velocity = 600f; // page units per second
        s.process(0f, 0f, 0.5f, t0, null);
        float[] out = null;
        float rawX = 0f;
        // Two seconds of ramp: the derivative estimate fully converges.
        for (int i = 1; i <= 480; i++) {
            rawX = velocity * (i * dtMs) / 1000f;
            out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
        }
        float lag = rawX - out[0];
        assertTrue("steady-state lag " + lag + " must be < 5 units",
                lag > 0f && lag < 5f);
    }

    /**
     * v2.0.0: POS_BETA 0.05 -> 0.07 and DCUTOFF 1 -> 2Hz. On the same 600
     * units/s 240Hz ramp the v1.4.21 constants trail 2.82 units; the new
     * constants trail 2.08 (simulation-verified). Require < 2.4 units so the
     * tuning cannot silently regress toward the old values.
     */
    @Test
    public void highSpeed_lagImproved_v2() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.2f, 0f));
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4;
        float velocity = 600f;
        s.process(0f, 0f, 0.5f, t0, null);
        float[] out = null;
        float rawX = 0f;
        for (int i = 1; i <= 480; i++) {
            rawX = velocity * (i * dtMs) / 1000f;
            out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
        }
        float lag = rawX - out[0];
        assertTrue("steady-state lag " + lag + " must be < 2.4 units (v2.0.0 tuning)",
                lag > 0f && lag < 2.4f);
    }

    /**
     * v2.1.0: the position low-pass floor at 100% smoothing was raised
     * (1.35 -> 2.2Hz) and POS_BETA (0.085 -> 0.09) so the filtered trace
     * keeps following a slow pen instead of stalling so hard that it snaps
     * direction on re-acceleration (the "max smoothing gives corners" feel).
     * A slow constant-velocity pen must still be covered by the output within
     * a few units, while the faster jitter is still suppressed.
     */
    @Test
    public void maxSmoothing_keepsUpOnSlowPen() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(1f, 0f));
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4;
        float velocity = 60f; // slow writing page units/s
        s.process(0f, 0f, 0.5f, t0, null);
        float rawEnd = 0f;
        float[] out = null;
        for (int i = 1; i <= 240; i++) { // one second
            float rawX = velocity * (i * dtMs) / 1000f;
            rawEnd = rawX;
            out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
        }
        assertTrue("max smoothing must still follow a slow pen: covered "
                        + out[0] + " of " + rawEnd,
                out[0] >= rawEnd * 0.8f);
    }

    @Test
    public void pressureStepRespondsWithinOneFrameGroup() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.2f, 0f));
        s.reset();
        long t0 = 1_000_000L;
        s.process(0f, 0f, 0.2f, t0, null);
        float[] out = null;
        for (int i = 1; i <= 8; i++) {
            out = s.process(0f, 0f, 0.8f, t0 + i * 4L, null);
        }
        assertTrue("pressure must react within ~32ms, got " + out[2],
                out[2] > 0.45f && out[2] < 0.9f);
    }

    /**
     * v2.1.0: a deliberate gradual press (not a step) must fatten the nib in
     * time, not trail most of the stroke behind. Base pressure cutoff was
     * raised 2.5 -> 3.2Hz so a ~80ms press reaches most of its target width.
     */
    @Test
    public void gradualPress_fattensInTime() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.2f, 0f));
        s.reset();
        long t0 = 1_000_000L;
        s.process(0f, 0f, 0.2f, t0, null);
        float[] out = null;
        for (int i = 1; i <= 20; i++) { // ~80ms
            float p = 0.2f + 0.7f * (i / 20f);
            out = s.process(0f, 0f, p, t0 + i * 4L, null);
        }
        assertTrue("gradual press must respond, got " + out[2],
                out[2] > 0.55f);
    }

    /**
     * v2.0.3: a 2D filter must not change its result when the same gesture is
     * rotated. The old independent X/Y adaptive cutoffs violated this at
     * corners because each axis observed a different speed and phase lag.
     */
    @Test
    public void highSmoothing_isRotationInvariantThroughTurn() {
        StrokeSmoother original = new StrokeSmoother();
        StrokeSmoother rotated = new StrokeSmoother();
        original.configure(preset(1f, 0f));
        rotated.configure(preset(1f, 0f));
        original.reset();
        rotated.reset();

        float[][] samples = {
                {0f, 0f}, {4f, 0f}, {8f, 0f}, {12f, 1f},
                {15f, 4f}, {16f, 8f}, {16f, 12f}, {15f, 16f}
        };
        float c = (float) (Math.sqrt(0.5));
        long t = 2_000_000L;
        for (float[] point : samples) {
            float[] a = original.process(point[0], point[1], 0.5f, t, null);
            float rx = (point[0] - point[1]) * c;
            float ry = (point[0] + point[1]) * c;
            float[] b = rotated.process(rx, ry, 0.5f, t, null);
            // Rotate the second result back by -45 degrees.
            float bx = (b[0] + b[1]) * c;
            float by = (-b[0] + b[1]) * c;
            assertEquals(a[0], bx, 2e-4f);
            assertEquals(a[1], by, 2e-4f);
            t += 4L;
        }
    }

    /**
     * v2.2.2: the pull-string stabilizer is a CONSTANT SPATIAL lag (exactly
     * its radius) — at the fountain pen's stabilization 0.1 that is 2.4 page
     * units, ~70 screen px at 1000% zoom, which read as "fast cursive can't
     * keep up". The radius must retract to zero on fast strokes: steady-state
     * lag at 900 u/s collapses to the 1€ filter lag alone (< 3 units; the old
     * fixed-radius behavior trailed 2.4 + ~2.5 ≈ 5).
     */
    @Test
    public void fastCursive_stabilizerRetracts() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0.2f, 0.1f)); // fountain pen: radius 2.4 units
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4;
        float velocity = 900f; // fast cursive
        s.process(0f, 0f, 0.5f, t0, null);
        float[] out = null;
        float rawX = 0f;
        for (int i = 1; i <= 480; i++) {
            rawX = velocity * (i * dtMs) / 1000f;
            out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
        }
        float lag = rawX - out[0];
        assertTrue("fast-cursive lag " + lag + " must be < 3 units "
                + "(retracted stabilizer + filter only)", lag > 0f && lag < 3f);
    }

    /**
     * v3.2.4 repro: a SHORT stroke written slowly at deep zoom collapses to
     * the anchor. The fountain pen's stabilization 0.1 gives a 2.4 page-unit
     * radius that is fully active at slow speed; the radius is a FIXED number
     * of page units, so at 1000% zoom (0.1 fit) it spans ~30 screen px and any
     * stroke shorter than that is held on the anchor and emits nothing —
     * "整段识别不出". The radius must be divided by the zoom so it stays a
     * constant number of screen pixels; a 1.92-unit stroke at 1000% must still
     * be followed, not collapsed.
     */
    @Test
    public void slowShortStroke_atDeepZoom_follows() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0f, 0.1f)); // fountain pen, isolate pull-string
        s.setZoomScaleFactor(0.1f);    // 1000% zoom: radius 2.4 -> 0.24 units
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4;
        float velocity = 30f; // very slow writing
        s.process(0f, 0f, 0.5f, t0, null);
        float minX = 0f, maxX = 0f;
        for (int i = 1; i <= 16; i++) { // 64ms, stroke ~1.92 page units
            float rawX = velocity * (i * dtMs) / 1000f;
            float[] out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
            minX = Math.min(minX, out[0]);
            maxX = Math.max(maxX, out[0]);
        }
        float span = maxX - minX;
        assertTrue("deep-zoom short stroke collapsed: emitted span " + span
                + " for a 1.92-unit stroke at 1000%", span > 0.2f);
    }

    /**
     * v2.2.2: retraction must not throw the stabilizer away for slow
     * writing, where it exists to damp hand jitter. At 120 u/s (< the full
     * radius speed) the anchor must still trail by most of its radius.
     */
    @Test
    public void slowWriting_stabilizerStillDamps() {
        StrokeSmoother s = new StrokeSmoother();
        s.configure(preset(0f, 0.1f)); // no filter: isolate the pull-string
        s.reset();
        long t0 = 1_000_000L;
        long dtMs = 4;
        float velocity = 120f;
        s.process(0f, 0f, 0.5f, t0, null);
        float[] out = null;
        float rawX = 0f;
        for (int i = 1; i <= 480; i++) {
            rawX = velocity * (i * dtMs) / 1000f;
            out = s.process(rawX, 0f, 0.5f, t0 + i * dtMs, null);
        }
        float lag = rawX - out[0];
        assertTrue("slow-writing stabilizer lag " + lag
                        + " must stay >= 2 units (still damping)",
                lag >= 2f && lag <= 2.5f);
    }
}
