package com.leslie.colorosnote.core.ink;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.StrokePoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Incremental quad emission, normals, welding and tail behavior. */
public class LiveOutlineBuilderTest {

    private static final float EPS = 1e-4f;

    @Test
    public void normalIsPerpendicularToStrokeDirection() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f);
        b.addPoint(20f, 0f, 4f);
        assertEquals(1, b.getQuadCount());
        float[] q = b.getQuads();
        // Direction +x -> left normal (0, +1): quad = {l0, l1, r1, r0}.
        assertEquals(0f, q[0], EPS);
        assertEquals(2f, q[1], EPS);
        assertEquals(10f, q[2], EPS);
        assertEquals(2f, q[3], EPS);
        assertEquals(10f, q[4], EPS);
        assertEquals(-2f, q[5], EPS);
        assertEquals(0f, q[6], EPS);
        assertEquals(-2f, q[7], EPS);
    }

    @Test
    public void quadOffsetsTrackPerPointWidth() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 2f);
        b.addPoint(10f, 0f, 8f);
        b.addPoint(20f, 0f, 8f);
        float[] q = b.getQuads();
        assertEquals(1f, q[1], EPS);   // start half width 1
        assertEquals(4f, q[3], EPS);   // end half width 4
        assertEquals(-4f, q[5], EPS);
        assertEquals(-1f, q[7], EPS);
    }

    @Test
    public void shortSegmentsAreWelded() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        // Width 4 -> weld distance 2; 0.5-spaced points all collapse.
        b.addPoint(0f, 0f, 4f);
        b.addPoint(0.5f, 0f, 4f);
        b.addPoint(1.0f, 0f, 4f);
        assertEquals(0, b.getQuadCount());
        assertFalse(b.tailQuad(new float[8]));
        // A point past the weld distance is accepted again.
        b.addPoint(5f, 0f, 4f);
        assertTrue(b.tailQuad(new float[8]));
    }

    @Test
    public void committedQuadsLagOneSegmentBehind() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        int n = 10;
        for (int i = 0; i < n; i++) {
            b.addPoint(i * 10f, 0f, 4f);
            // i+1 accepted points -> max(0, i-1) committed quads.
            assertEquals(Math.max(0, i - 1), b.getQuadCount());
        }
        // The tail always covers the newest segment.
        float[] tail = new float[8];
        assertTrue(b.tailQuad(tail));
        assertEquals(80f, tail[0], EPS); // starts at the 9th point
        assertEquals(90f, tail[2], EPS); // ends at the 10th
    }

    @Test
    public void consecutiveQuadsShareExactJointChord() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        // Zigzag: sharp corners are where mismatched joint chords would gap.
        float[][] pts = {
                {0f, 0f, 4f}, {10f, 0f, 4f}, {10f, 10f, 6f},
                {20f, 10f, 3f}, {20f, 0f, 5f},
        };
        for (float[] p : pts) {
            b.addPoint(p[0], p[1], p[2]);
        }
        float[] q = b.getQuads();
        for (int i = 0; i + 1 < b.getQuadCount(); i++) {
            int end = i * 8 + 2;       // quad i end chord (l1, r1)
            int start = (i + 1) * 8;   // quad i+1 start chord (l0, r0)
            assertEquals(q[end], q[start], 0f);
            assertEquals(q[end + 1], q[start + 1], 0f);
            assertEquals(q[end + 2], q[start + 6], 0f);
            assertEquals(q[end + 3], q[start + 7], 0f);
        }
    }

    @Test
    public void tailStartChordMatchesLastCommittedQuad() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f);
        b.addPoint(10f, 10f, 4f);
        float[] tail = new float[8];
        assertTrue(b.tailQuad(tail));
        float[] q = b.getQuads();
        // The bend is spline-subdivided (v1.4.18): compare against the LAST
        // committed quad. Tail start chord == its end chord (seamless joint).
        int o = (b.getQuadCount() - 1) * 8;
        assertEquals(q[o + 2], tail[0], 0f);
        assertEquals(q[o + 3], tail[1], 0f);
        assertEquals(q[o + 4], tail[6], 0f);
        assertEquals(q[o + 5], tail[7], 0f);
    }

    @Test
    public void resetClearsAllState() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f);
        b.addPoint(20f, 0f, 4f);
        b.reset();
        assertEquals(0, b.getQuadCount());
        assertFalse(b.tailQuad(new float[8]));
        assertFalse(b.getLastPoint(new float[3]));
    }

    @Test
    public void bufferGrowsBeyondInitialCapacity() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        for (int i = 0; i < 200; i++) {
            b.addPoint(i * 10f, 0f, 4f);
        }
        assertEquals(198, b.getQuadCount());
    }

    @Test
    public void headCapIsSemicircleBulgingBackward() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        assertEquals(0, b.headCap(new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2]));
        b.addPoint(0f, 0f, 4f);
        assertEquals(0, b.headCap(new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2]));
        b.addPoint(10f, 0f, 4f); // head normal final once 2nd point lands
        float[] cap = new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2];
        int n = b.headCap(cap);
        assertEquals(StrokeMesh.CAP_SEGMENTS + 1, n);
        // Stroke runs +x, radius 2: the semicircle is centered on the head
        // point (0,0) and bulges backward to x = -2.
        float minX = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            assertEquals(2f, (float) Math.hypot(cap[i * 2], cap[i * 2 + 1]), EPS);
            minX = Math.min(minX, cap[i * 2]);
        }
        assertEquals(-2f, minX, EPS);
        // Chord endpoints are the head's left/right outline points.
        assertEquals(0f, cap[0], EPS);
        assertEquals(2f, cap[1], EPS);
        assertEquals(0f, cap[(n - 1) * 2], EPS);
        assertEquals(-2f, cap[(n - 1) * 2 + 1], EPS);
    }

    @Test
    public void tailCapIsSemicircleBulgingForward() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f);
        b.addPoint(20f, 0f, 6f); // tail width 6 -> radius 3
        float[] cap = new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2];
        int n = b.tailCap(cap);
        assertEquals(StrokeMesh.CAP_SEGMENTS + 1, n);
        // Centered on the newest point (20,0), bulging forward to x = 23.
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            assertEquals(3f,
                    (float) Math.hypot(cap[i * 2] - 20f, cap[i * 2 + 1]), EPS);
            maxX = Math.max(maxX, cap[i * 2]);
        }
        assertEquals(23f, maxX, EPS);
        // Chord endpoints match the tail quad's end chord (seamless joint).
        float[] tail = new float[8];
        assertTrue(b.tailQuad(tail));
        assertEquals(tail[2], cap[0], 0f);
        assertEquals(tail[3], cap[1], 0f);
        assertEquals(tail[4], cap[(n - 1) * 2], 0f);
        assertEquals(tail[5], cap[(n - 1) * 2 + 1], 0f);
    }

    @Test
    public void capsResetWithStroke() {
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f);
        b.reset();
        assertEquals(0, b.headCap(new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2]));
        assertEquals(0, b.tailCap(new float[(StrokeMesh.CAP_SEGMENTS + 1) * 2]));
    }

    @Test
    public void curvedSegmentCommitsSubdividedQuads() {
        // v1.4.18: a bending segment commits several quads (smooth curve),
        // not one straight chord.
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        int n = 8;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            b.addPoint(100f + 60f * (float) Math.cos(a),
                    100f + 60f * (float) Math.sin(a), 4f);
        }
        assertTrue("bends subdivide", b.getQuadCount() > n - 2);
        // Subdivided or not, every consecutive quad still shares the exact
        // joint chord (no seams).
        float[] q = b.getQuads();
        for (int i = 0; i + 1 < b.getQuadCount(); i++) {
            int end = i * 8 + 2;
            int start = (i + 1) * 8;
            assertEquals(q[end], q[start], 0f);
            assertEquals(q[end + 1], q[start + 1], 0f);
            assertEquals(q[end + 2], q[start + 6], 0f);
            assertEquals(q[end + 3], q[start + 7], 0f);
        }
    }

    @Test
    public void straightSegmentEmitsNoSagittaCover() {
        // Even at deep-zoom scale (64), a straight run bends nowhere, so the
        // live path needs no sagitta-cover discs.
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(64f);
        for (int i = 0; i < 6; i++) {
            b.addPoint(i * 20f, 0f, 4f);
        }
        assertEquals(0, b.getMidpointCount());
        assertTrue(b.getQuadCount() > 0);
    }

    @Test
    public void gentleCurvesEmitCoverDiscsToo() {
        // v2.1.4: every bent segment emits cover discs — the outer rim of a
        // WIDE stroke sags by (R + w/2)(1 - cos), growing with the width,
        // while subdivision tolerances only bound the centerline sagitta
        // (widened pencils showed hairline crescents under the v2.1.3
        // on-demand rule). Straight runs are unaffected
        // (straightSegmentEmitsNoSagittaCover); the per-frame cost is
        // absorbed by InkView's bake-bitmap pipeline.
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(6f);
        int n = 6;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            b.addPoint(100f + 60f * (float) Math.cos(a),
                    100f + 60f * (float) Math.sin(a), 4f);
        }
        assertTrue("curved run subdivides", b.getQuadCount() > n - 2);
        assertTrue("bent segments carry discs, got " + b.getMidpointCount(),
                b.getMidpointCount() > 0);
    }

    @Test
    public void cappedDeepZoomBendsKeepDiscBlanket() {
        // v2.2.0: where the live cap really binds (deep-zoom sharp bends),
        // width-adaptive discs still blanket the outer bulge: neighbors stay
        // within ~0.85·width along the spline, so the union is seamless.
        // Three 30° segments at 64x: angular demand 40 > cap 24. Only the
        // first two are committed (the newest stays in the transient tail).
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(64f);
        int n = 4;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            b.addPoint(100f + 60f * (float) Math.cos(a),
                    100f + 60f * (float) Math.sin(a), 4f);
        }
        assertEquals("every capped segment subdivides to the cap",
                2 * 24, b.getQuadCount());
        // Per segment: ceil(1.15·chord / (0.85·w)) with chord≈31, w=4 → 11.
        int minExpected = 2 * 8;
        int maxExpected = 2 * 16;
        assertTrue("disc blanket on capped bends, got " + b.getMidpointCount(),
                b.getMidpointCount() >= minExpected
                        && b.getMidpointCount() <= maxExpected);
        float[] m = b.getMidpoints();
        for (int i = 0; i < b.getMidpointCount(); i++) {
            assertTrue("cover center within stroke bounds",
                    m[i * 3] > 38f && m[i * 3] < 162f
                            && m[i * 3 + 1] > 38f && m[i * 3 + 1] < 162f);
        }
    }

    @Test
    public void wideBrushDiscCountStaysBounded() {
        // v2.2.0 latency fix: disc count scales DOWN as the brush widens
        // (fewer, larger discs). The old scheme pinned 2·sub ≈ 48 discs per
        // segment regardless of width, stalling fast wide-brush handwriting
        // at deep zoom. A wide 30° bend segment (chord≈31, w=24) needs only
        // ceil(1.15·31/(0.85·24)) = 2 discs.
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(64f);
        int n = 4;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            b.addPoint(100f + 60f * (float) Math.cos(a),
                    100f + 60f * (float) Math.sin(a), 24f);
        }
        assertTrue("wide brush still subdivides for smoothness, got "
                + b.getQuadCount(), b.getQuadCount() >= 2);
        assertTrue("wide-brush disc budget exceeded, got "
                + b.getMidpointCount(), b.getMidpointCount() <= 8);
    }

    @Test
    public void tailStripSubdividesBendsAndCoversWithDiscs() {
        // v2.2.0: the transient tail (newest segment) is spline-subdivided
        // with per-interval cover discs, so the pen tip hugs bends instead of
        // tearing (the old single straight tail quad).
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(64f);
        b.addPoint(0f, 0f, 4f);
        b.addPoint(10f, 0f, 4f); // straight so far: single quad, one disc
        assertEquals(1, b.getTailQuadCount());
        assertEquals(1, b.getTailDiscCount());
        b.addPoint(10f, 20f, 4f); // sharp bend: strip subdivides
        assertTrue("bent tail subdivides, got " + b.getTailQuadCount(),
                b.getTailQuadCount() >= 2);
        assertEquals("one cover disc per sub-interval",
                b.getTailQuadCount(), b.getTailDiscCount());
        // Tail quads chain seamlessly: each quad's end chord is the next
        // quad's start chord.
        float[] tq = b.getTailQuads();
        for (int i = 0; i + 1 < b.getTailQuadCount(); i++) {
            int end = i * 8 + 2;
            int start = (i + 1) * 8;
            assertEquals(tq[end], tq[start], 0f);
            assertEquals(tq[end + 1], tq[start + 1], 0f);
            assertEquals(tq[end + 2], tq[start + 6], 0f);
            assertEquals(tq[end + 3], tq[start + 7], 0f);
        }
    }

    @Test
    public void deepZoomSharpBendIsCappedForLatency() {
        // v2.1.2: a 90° bend over a long segment at deep zoom would
        // angularly subdivide to 100+ sub-quads; the live builder must cap
        // the per-segment subdivision (the cover discs absorb the error).
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        b.setPixelsPerPageUnit(64f);
        b.addPoint(0f, 0f, 4f);
        b.addPoint(5f, 0f, 4f);
        b.addPoint(5f, 5f, 4f);
        b.addPoint(10f, 5f, 4f);
        // 2 committed segments; each may emit at most the live cap quads.
        assertTrue(b.getQuadCount() >= 2);
        assertTrue("per-segment subdivision capped, got " + b.getQuadCount(),
                b.getQuadCount() <= 2 * 24);
    }

    @Test
    public void liveAndCommittedOutlinesMatchAtKnots() {
        // The two render paths must produce the same shape: the end chord of
        // every committed live segment equals the committed mesh's outline
        // chord at the same knot (BrushEngine.computeMesh).
        int n = 8;
        float[] xs = new float[n];
        float[] ys = new float[n];
        List<StrokePoint> pts = new ArrayList<>();
        LiveOutlineBuilder b = new LiveOutlineBuilder();
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            float x = 100f + 60f * (float) Math.cos(a);
            float y = 100f + 60f * (float) Math.sin(a);
            xs[i] = x;
            ys[i] = y;
            pts.add(new StrokePoint(x, y, i * 8L, 0.5f));
            b.addPoint(x, y, 2.5f);
        }
        StrokeMesh mesh = BrushEngine.computeMesh(pts, BrushPreset.ballpoint());
        // With n accepted points the builder commits segments 0..n-3 (the
        // newest segment stays in the transient tail).
        float[] q = b.getQuads();
        int quad = 0;
        int knotIdx = 0;
        for (int seg = 0; seg < n - 2; seg++) {
            int sub = subdivisionsClamped(xs, ys, seg, n);
            quad += sub;
            knotIdx += sub;
            int o = (quad - 1) * 8;
            assertEquals(mesh.left[knotIdx * 2], q[o + 2], 1e-3f);
            assertEquals(mesh.left[knotIdx * 2 + 1], q[o + 3], 1e-3f);
            assertEquals(mesh.right[knotIdx * 2], q[o + 4], 1e-3f);
            assertEquals(mesh.right[knotIdx * 2 + 1], q[o + 5], 1e-3f);
        }
    }

    /** Segment subdivision count with the mesh's end-padded controls. */
    private static int subdivisionsClamped(float[] xs, float[] ys, int seg, int n) {
        int i0 = Math.max(0, seg - 1);
        int i3 = Math.min(n - 1, seg + 2);
        return SplineRefiner.subdivisions(xs[i0], ys[i0], xs[seg], ys[seg],
                xs[seg + 1], ys[seg + 1], xs[i3], ys[i3]);
    }
}
