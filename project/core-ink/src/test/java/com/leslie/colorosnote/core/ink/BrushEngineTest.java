package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Width model, tapers and mesh structure. */
public class BrushEngineTest {

    private static List<StrokePoint> straightLine(float y, float fromX, float toX,
                                                  float pressure, int count) {
        List<StrokePoint> pts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float x = fromX + (toX - fromX) * i / (count - 1);
            pts.add(new StrokePoint(x, y, i * 8L, pressure));
        }
        return pts;
    }

    @Test
    public void fountainPen_pressureScalesWidthWithinRange() {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.taperStart = 0f;
        preset.taperEnd = 0f;
        preset.velocityToWidth = 0f;
        BrushEngine engine = new BrushEngine();
        engine.beginStroke(preset);
        float low = 0, mid = 0, high = 0;
        float[] out = new float[4];
        for (StrokePoint p : straightLine(0f, 0f, 100f, 0.05f, 10)) {
            low = engine.addPoint(p, out)[2];
        }
        engine.endStroke();
        engine.beginStroke(preset);
        for (StrokePoint p : straightLine(0f, 0f, 100f, 0.5f, 10)) {
            mid = engine.addPoint(p, out)[2];
        }
        engine.endStroke();
        engine.beginStroke(preset);
        for (StrokePoint p : straightLine(0f, 0f, 100f, 1f, 10)) {
            high = engine.addPoint(p, out)[2];
        }
        engine.endStroke();
        assertTrue(low < mid);
        assertTrue(mid < high);
        assertTrue("low pressure >= ~minWidth", low >= preset.minWidth * 0.99f);
        assertTrue("high pressure <= maxWidth", high <= preset.maxWidth + 1e-4f);
        // Recommended band: roughly 0.4x..1.9x of baseWidth.
        assertTrue(low >= preset.baseWidth * 0.35f);
        assertTrue(high <= preset.baseWidth * 2.0f + 1e-4f);
    }

    @Test
    public void velocity_fasterMeansThinner() {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.velocityToWidth = 1f;
        preset.taperStart = 0f;
        preset.taperEnd = 0f;
        StrokePoint slow = new StrokePoint(50f, 50f, 0L, 0.5f);
        StrokePoint fast = new StrokePoint(50f, 50f, 8L, 0.5f);
        fast.velocityX = 1500f; // at the reference speed

        BrushEngine engine = new BrushEngine();
        engine.beginStroke(preset);
        engine.addPoint(new StrokePoint(0f, 50f, 0L, 0.5f), null);
        float wSlow = engine.addPoint(slow, null)[2];
        engine.endStroke();

        engine.beginStroke(preset);
        engine.addPoint(new StrokePoint(0f, 50f, 0L, 0.5f), null);
        float wFast = engine.addPoint(fast, null)[2];
        engine.endStroke();
        assertTrue(wFast < wSlow);
    }

    @Test
    public void velocity_v2_midSpeedKeepsWidthFlicksStillThin() {
        // v2.0.0: VELOCITY_REF 1500 -> 1300 and the mapping smoothstep ->
        // smoothstep^1.5. At half reference speed the width penalty drops
        // from 0.464 (v1.4.21 at 650 u/s) to smoothstep(0.5)^1.5 = 0.354,
        // while full thinning still applies at/above the reference speed.
        BrushPreset preset = BrushPreset.fountainPen();
        preset.pressureToWidth = 0f; // isolate the velocity factor
        preset.taperStart = 0f;
        preset.taperEnd = 0f;
        preset.velocityToWidth = 1f;

        BrushEngine engine = new BrushEngine();
        StrokePoint a = new StrokePoint(0f, 50f, 0L, 0.5f);
        StrokePoint b = new StrokePoint(4f, 50f, 8L, 0.5f);
        a.velocityX = 650f; // both points in the 40ms window -> avg = 650
        b.velocityX = 650f;
        engine.beginStroke(preset);
        engine.addPoint(a, null);
        float wMid = engine.addPoint(b, null)[2];
        engine.endStroke();
        float expected = preset.baseWidth
                * (1f - (float) Math.pow(0.5f, 1.5f)); // smoothstep(0.5) = 0.5
        assertEquals(expected, wMid, 1e-3f);

        a = new StrokePoint(0f, 50f, 0L, 0.5f);
        b = new StrokePoint(4f, 50f, 8L, 0.5f);
        a.velocityX = 1400f; // beyond the 1300 reference -> full thinning
        b.velocityX = 1400f;
        engine.beginStroke(preset);
        engine.addPoint(a, null);
        float wFast = engine.addPoint(b, null)[2];
        engine.endStroke();
        assertTrue("flick remains strongly thinner", wFast < preset.baseWidth * 0.25f);
        assertTrue("flick never collapses into missing ink",
                wFast >= preset.baseWidth * 0.21f);
    }

    @Test
    public void taper_startAndEndAreNarrower() {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.taperStart = 0.5f; // 15 page units at baseWidth 3
        preset.taperEnd = 0.5f;
        List<StrokePoint> pts = straightLine(100f, 0f, 200f, 0.5f, 40);
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        int n = mesh.pointCount();
        float mid = mesh.widths[n / 2];
        assertTrue("start tapered", mesh.widths[0] < mid * 0.3f);
        assertTrue("end tapered", mesh.widths[n - 1] < mid * 0.3f);
        assertTrue(mid > 0f);
    }

    @Test
    public void mesh_structureAndBounds() {
        BrushPreset preset = BrushPreset.ballpoint();
        preset.taperEnd = 0f;
        List<StrokePoint> pts = straightLine(50f, 0f, 100f, 0.5f, 20);
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        int n = mesh.pointCount();
        assertEquals(n * 2, mesh.centerline.length);
        assertEquals(n * 2, mesh.left.length);
        assertEquals(n * 2, mesh.right.length);
        // Closed outline: 2n points + interior cap arc points.
        int expectedOutline = (2 * n + 2 * (StrokeMesh.CAP_SEGMENTS - 1)) * 2;
        assertEquals(expectedOutline, mesh.closedOutline().length);
        // Bounds include half width on both sides of y=50.
        assertTrue(mesh.bounds[1] < 50f);
        assertTrue(mesh.bounds[3] > 50f);
        assertTrue(mesh.bounds[0] <= 0f + preset.maxWidth);
    }

    @Test
    public void highlighter_constantWidthAndTranslucent() {
        BrushPreset preset = BrushPreset.highlighter();
        List<StrokePoint> pts = straightLine(0f, 0f, 100f, 0.9f, 12);
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        for (float w : mesh.widths) {
            assertEquals(preset.baseWidth, w, 1e-3f);
        }
        assertEquals(0.4f, mesh.averageOpacity(), 1e-3f);
    }

    @Test
    public void singleTap_producesDotMesh() {
        BrushPreset preset = BrushPreset.ballpoint();
        List<StrokePoint> pts = new ArrayList<>();
        pts.add(new StrokePoint(10f, 10f, 0L, 0.5f));
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        assertEquals(2, mesh.pointCount()); // degenerate point duplicated
        assertTrue(mesh.closedOutline().length >= 6);
    }

    /**
     * v4.0.0: a tap with slight pen drift (total length below half the pen
     * width) sits inside BOTH taper ramps at once, and after the centerline
     * weld only the two endpoints survive — both at the taper minimum — so
     * the dot rendered as a sub-pixel hairline ("1000% 下打点有时候消失").
     * Dot-scale strokes skip the tapers and render at full pen width.
     */
    @Test
    public void driftedDot_skipsTapersAndStaysFullWidthAtDeepZoom() {
        BrushPreset pen = BrushPreset.fountainPen(); // tapers on, baseWidth 3
        List<StrokePoint> dot = new ArrayList<>();
        dot.add(new StrokePoint(50f, 50f, 0L, 0.5f));
        dot.add(new StrokePoint(50.3f, 50.2f, 4L, 0.5f));
        dot.add(new StrokePoint(50.5f, 50.4f, 8L, 0.5f));
        StrokeMesh mesh = BrushEngine.computeMesh(dot, pen, 12.5f); // 1000% zoom
        float max = 0f;
        for (float w : mesh.widths) {
            max = Math.max(max, w);
        }
        assertTrue("drifted dot renders at (near) full pen width, not a hairline",
                max >= pen.minWidth * 0.9f);
    }

    /**
     * v4.0.0: the live stroke applies the visible-width floor once more AFTER
     * the start taper, so a tap — which lives its whole life at the taper
     * minimum — stays on screen while it is drawn.
     */
    @Test
    public void liveDot_visibleFloorAppliesAfterStartTaper() {
        BrushPreset pen = BrushPreset.fountainPen();
        pen.baseWidth = 1f; // taper minimum 0.2 — below the floor without the fix
        pen.pressureEnabled = false;
        pen.pressureToWidth = 0f;
        pen.velocityToWidth = 0f; // width == baseWidth before the taper
        BrushEngine engine = new BrushEngine();
        engine.setMinVisibleWidthPage(0.5f); // exaggerated floor: proves the clamp
        engine.beginStroke(pen);
        float w = engine.addPoint(new StrokePoint(50f, 50f, 0L, 0.5f), null)[2];
        engine.endStroke();
        assertTrue("first live point sits at the taper minimum but stays visible",
                w >= 0.5f - 1e-6f);
    }

    @Test
    public void ballpoint_constantWidthNoTaperRegardlessOfPressure() {
        BrushPreset preset = BrushPreset.ballpoint();
        // Fixed line width at any pressure, and round (un-tapered) ends:
        // first and last widths equal the middle width.
        for (float pressure : new float[]{0.05f, 0.5f, 1f}) {
            StrokeMesh mesh = BrushEngine.computeMesh(
                    straightLine(0f, 0f, 200f, pressure, 40), preset);
            for (float w : mesh.widths) {
                assertEquals(preset.baseWidth, w, 1e-3f);
            }
        }
    }

    @Test
    public void pencil_constantWidthPressureOnlyDarkens() {
        BrushPreset preset = BrushPreset.pencilHb();
        StrokeMesh light = BrushEngine.computeMesh(
                straightLine(0f, 0f, 200f, 0.05f, 40), preset);
        StrokeMesh heavy = BrushEngine.computeMesh(
                straightLine(0f, 0f, 200f, 1f, 40), preset);
        for (float w : light.widths) {
            assertEquals(preset.baseWidth, w, 1e-3f);
        }
        for (float w : heavy.widths) {
            assertEquals(preset.baseWidth, w, 1e-3f);
        }
        assertTrue("heavier pressure draws darker",
                heavy.averageOpacity() > light.averageOpacity());
    }

    @Test
    public void ballpoint_capsAreFullSemicircles() {
        // Round caps: the outline must extend exactly width/2 beyond both
        // endpoint centers, and every cap vertex must sit on the circle of
        // radius width/2 around the endpoint (a pointed/triangular end would
        // have its foremost vertices closer than the radius).
        BrushPreset preset = BrushPreset.ballpoint();
        List<StrokePoint> pts = straightLine(50f, 0f, 200f, 0.5f, 40);
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        int n = mesh.pointCount();
        int capPts = StrokeMesh.CAP_SEGMENTS - 1;
        float[] outline = mesh.closedOutline();
        float r = mesh.widths[n - 1] / 2f;

        // End cap arc vertices: right after the n left-outline points.
        float ecx = mesh.centerline[(n - 1) * 2];
        float ecy = mesh.centerline[(n - 1) * 2 + 1];
        for (int i = 0; i < capPts; i++) {
            float x = outline[(n + i) * 2];
            float y = outline[(n + i) * 2 + 1];
            assertEquals(r, (float) Math.hypot(x - ecx, y - ecy), 1e-3f);
        }
        // Start cap arc vertices: after the n right-outline points.
        float scx = mesh.centerline[0];
        float scy = mesh.centerline[1];
        int startBase = 2 * n + capPts;
        for (int i = 0; i < capPts; i++) {
            float x = outline[(startBase + i) * 2];
            float y = outline[(startBase + i) * 2 + 1];
            assertEquals(r, (float) Math.hypot(x - scx, y - scy), 1e-3f);
        }

        // The capsule extends the full radius beyond both endpoint centers
        // (stroke runs +x: end cap tip at ecx + r, start cap tip at scx - r).
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < outline.length; i += 2) {
            minX = Math.min(minX, outline[i]);
            maxX = Math.max(maxX, outline[i]);
        }
        assertEquals(ecx + r, maxX, 1e-3f);
        assertEquals(scx - r, minX, 1e-3f);
    }

    @Test
    public void capsAreSmoothlyTessellated() {
        // v1.4.17: 6 segments made the cap a hexagon with a vertex exactly at
        // the tip (visibly pointed); require a dense tessellation.
        assertTrue(StrokeMesh.CAP_SEGMENTS >= 16);
        assertTrue("deep zoom/wide nib needs more than the fit-zoom cap",
                StrokeMesh.capSegmentsForWidth(20f, 64f)
                        > StrokeMesh.CAP_SEGMENTS);
    }

    @Test
    public void curvedStrokeIsSmoothNotPolygonal() {
        // v1.4.18: 12 samples on a quarter arc used to render as 11 straight
        // chords (~7.5 degree facets, a visible polygon); after Catmull-Rom
        // refinement consecutive centerline segments turn by a few degrees.
        BrushPreset preset = BrushPreset.ballpoint();
        List<StrokePoint> pts = new ArrayList<>();
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a = Math.PI / 2 * i / (n - 1);
            pts.add(new StrokePoint(
                    100f + 60f * (float) Math.cos(a),
                    100f + 60f * (float) Math.sin(a), i * 8L, 0.5f));
        }
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        assertTrue("curve subdivided", mesh.pointCount() > n);
        float prevAngle = Float.NaN;
        float maxTurn = 0f;
        for (int i = 1; i < mesh.pointCount(); i++) {
            float dx = mesh.centerline[i * 2] - mesh.centerline[(i - 1) * 2];
            float dy = mesh.centerline[i * 2 + 1] - mesh.centerline[(i - 1) * 2 + 1];
            if (dx * dx + dy * dy < 1e-9f) {
                continue;
            }
            float ang = (float) Math.atan2(dy, dx);
            if (!Float.isNaN(prevAngle)) {
                float turn = Math.abs(ang - prevAngle);
                if (turn > (float) Math.PI) {
                    turn = 2f * (float) Math.PI - turn;
                }
                maxTurn = Math.max(maxTurn, turn);
            }
            prevAngle = ang;
        }
        assertTrue("max centerline turn "
                        + Math.toDegrees(maxTurn) + " deg",
                maxTurn < (float) Math.toRadians(9.0));
    }

    @Test
    public void straightStrokeIsNotSubdivided() {
        // Performance guard: refinement must not add vertices to straight
        // runs (live-commit cost and mesh size stay as before).
        BrushPreset preset = BrushPreset.ballpoint();
        List<StrokePoint> pts = straightLine(50f, 0f, 200f, 0.5f, 40);
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset);
        assertEquals(40, mesh.pointCount());
    }

    @Test
    public void tinyPresetWidthIsNotClampedThicker() {
        // v1.4.19: the width slider now goes down to 0.5pt; the engine must
        // render exactly the preset width (no silent floor at a thicker
        // value). Fixed-width brushes keep min==max==base.
        BrushPreset ball = BrushPreset.ballpoint();
        ball.baseWidth = 0.5f;
        ball.minWidth = 0.5f;
        ball.maxWidth = 0.5f;
        StrokeMesh mesh = BrushEngine.computeMesh(
                straightLine(0f, 0f, 200f, 0.5f, 40), ball);
        for (float w : mesh.widths) {
            assertEquals(0.5f, w, 1e-3f);
        }
        // Bounds still reflect the half-width outline, not a clamped width.
        assertTrue("outline spans 0.5pt", mesh.bounds[3] - mesh.bounds[1] <= 0.5f + 1e-3f);

        // Fountain pen at the new slider floor: light pressure may taper to
        // 0.4x base but must never fall below minWidth.
        BrushPreset pen = BrushPreset.fountainPen();
        pen.baseWidth = 0.5f;
        pen.minWidth = 0.5f * 0.4f;
        pen.maxWidth = 0.5f * 1.9f;
        pen.taperStart = 0f;
        pen.taperEnd = 0f;
        pen.velocityToWidth = 0f;
        StrokeMesh light = BrushEngine.computeMesh(
                straightLine(0f, 0f, 200f, 0.05f, 40), pen);
        for (float w : light.widths) {
            assertTrue("light-pressure width >= minWidth", w >= pen.minWidth * 0.99f);
        }
    }

    @Test
    public void tinyWidth_isRaisedToVisibleAtRealRenderScale() {
        // v3.2.2: at the renderer's real device scale (fit zoom and deeper,
        // always > 1 px per page unit) a 0.5pt body is ~0.95 px at fit zoom —
        // anti-aliased to nothing, which read as "the thin pen sometimes
        // doesn't register at all" (fountain pen with light pressure and
        // short cursive strokes). The floor is applied BEFORE the tapers so
        // the body is readable while the pen tip still fades out.
        float scale = 1.9f; // fit-zoom device px per page unit
        BrushPreset ball = BrushPreset.ballpoint();
        ball.baseWidth = 0.5f;
        ball.minWidth = 0.5f;
        ball.maxWidth = 0.5f;
        StrokeMesh mesh = BrushEngine.computeMesh(
                straightLine(0f, 0f, 200f, 0.5f, 40), ball, scale);
        for (float w : mesh.widths) {
            assertTrue("0.5pt body lifted to >= 1 device px at fit zoom",
                    w >= 1f / scale - 1e-3f);
        }

        BrushPreset pen = BrushPreset.fountainPen();
        pen.baseWidth = 0.5f;
        pen.minWidth = 0.5f * 0.4f;
        pen.maxWidth = 0.5f * 1.9f;
        pen.velocityToWidth = 0f;
        StrokeMesh t = BrushEngine.computeMesh(
                straightLine(0f, 0f, 40f, 0.5f, 40), pen, scale);
        float mid = t.widths[t.pointCount() / 2];
        assertTrue("taper tip is a real fade, much thinner than the body",
                t.widths[0] < mid * 0.5f);
        assertTrue("taper tip still has ink", t.widths[0] > 0f);
    }
}
