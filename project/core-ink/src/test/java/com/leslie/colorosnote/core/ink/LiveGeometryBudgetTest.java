package com.leslie.colorosnote.core.ink;

import static org.junit.Assert.assertTrue;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

/**
 * Counts the geometry the live pipeline actually emits per brush, for one fast
 * handwriting stroke at the 1000% zoom cap.
 *
 * <p>Written to settle a specific report: after the v3.1.x fixes the ballpoint
 * and pencil keep up at 1000%, but the fountain pen still lags. The fountain is
 * the only preset whose width varies (pressure + velocity + taper), so the
 * question is whether that variation multiplies the per-sample geometry — which
 * cannot be measured through adb, because injected strokes are straight lines
 * at constant pressure.
 */
public class LiveGeometryBudgetTest {

    /** A4 fitted on the pad, at the 10x zoom cap, capped by the live policy. */
    private static final float TESS_SCALE = 12.5f;

    @Test
    public void fountainPenDoesNotEmitFarMoreGeometryThanAConstantWidthPen() {
        Budget fountain = run(BrushPreset.fountainPen());
        Budget ballpoint = run(BrushPreset.ballpoint());
        Budget pencil = run(BrushPreset.pencilHb());

        System.out.println("fountain  " + fountain);
        System.out.println("ballpoint " + ballpoint);
        System.out.println("pencil    " + pencil);

        // The fountain legitimately needs a little more geometry (its width
        // changes, so segments bend more), but "a little" must not mean
        // multiples — that is what shows up as it being the one brush that
        // cannot keep up.
        float ratio = (float) fountain.total() / Math.max(1, ballpoint.total());
        assertTrue("fountain emits " + ratio + "x the geometry of the ballpoint"
                        + " (" + fountain + " vs " + ballpoint + ")",
                ratio <= 2.0f);
    }

    /**
     * The smoothing slider at 0 is documented as "raw pass-through": the 1€
     * filter is bypassed entirely. Real pen samples are jittery, so every bit
     * of that jitter becomes a direction change, and direction changes are
     * exactly what {@link SplineRefiner} subdivides on. This measures what that
     * costs, because the user's fountain preset is saved with smoothing 0 while
     * the ballpoint and pencil defaults are 0.2 — a per-brush difference in
     * their own settings rather than in the engine.
     */
    @Test
    public void rawPassThroughSmoothingCostsFarMoreGeometryOnJitteryInput() {
        Budget smoothed = runJittery(0.2f);
        Budget raw = runJittery(0.0f);
        System.out.println("smoothing=0.2 " + smoothed);
        System.out.println("smoothing=0.0 " + raw);
        float ratio = (float) raw.total() / Math.max(1, smoothed.total());
        System.out.println("raw/smoothed geometry ratio = " + ratio);
        // Measured at 1.02x. Recorded as a guard: turning smoothing off is NOT
        // a geometry-cost problem, so "the fountain is slow because its
        // smoothing is 0" is ruled out as an explanation.
        assertTrue("raw pass-through unexpectedly multiplied geometry: " + ratio,
                ratio <= 1.5f);
    }

    /** Same stroke, but with realistic sample jitter run through the smoother. */
    private static Budget runJittery(float smoothing) {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.smoothing = smoothing;
        preset.velocityToWidth = 0f; // match the user's saved fountain
        BrushEngine engine = new BrushEngine();
        engine.beginStroke(preset);
        StrokeSmoother smoother = new StrokeSmoother();
        smoother.configure(preset);
        LiveOutlineBuilder outline = new LiveOutlineBuilder();
        outline.setPixelsPerPageUnit(TESS_SCALE);
        outline.reset();

        Budget b = new Budget();
        float[] out = new float[4];
        java.util.Random rng = new java.util.Random(42L);
        int samples = 240;
        float radius = 9f;
        float[] sm = new float[3];
        for (int i = 0; i < samples; i++) {
            float t = i / (float) (samples - 1);
            float theta = t * (float) Math.PI * 6f;
            // ±0.08 page units of sensor noise — well under a screen pixel at
            // fit zoom, but one full pixel at the 1000% cap.
            float jx = (rng.nextFloat() - 0.5f) * 0.16f;
            float jy = (rng.nextFloat() - 0.5f) * 0.16f;
            float x = 40f + t * 40f + radius * (float) Math.cos(theta) + jx;
            float y = 40f + radius * (float) Math.sin(theta) * 0.6f + jy;
            long ts = 1000L + (long) (i * (1000f / 240f));
            smoother.process(x, y, 0.5f, ts, sm);

            StrokePoint p = new StrokePoint();
            p.x = sm[0];
            p.y = sm[1];
            p.pressure = sm[2];
            p.timestampMs = ts;
            engine.addPoint(p, out);
            outline.addPoint(out[0], out[1], out[2]);
        }
        b.knots = samples;
        b.quads = outline.getQuadCount();
        b.discs = outline.getMidpointCount();
        return b;
    }

    /**
     * The decisive measurement for the "thin lines lag, thick lines are fine"
     * report: sweep the pen width over one identical fast cursive stroke at the
     * 1000% cap and count the geometry each width produces.
     *
     * <p>Several thresholds in the pipeline are proportional to the stroke
     * width — the vertex weld is {@code 0.5 * width}, and the sagitta cover
     * discs are emitted as {@code chord / (0.85 * width)}, i.e. INVERSELY
     * proportional. A thinner pen therefore keeps more knots and stamps more
     * discs per knot for exactly the same hand movement.
     */
    @Test
    public void thinPensDoNotExplodeTheGeometryBudget() {
        Budget w05 = runWidth(0.5f);
        Budget w10 = runWidth(1.0f);
        Budget w30 = runWidth(3.0f);
        Budget w80 = runWidth(8.0f);
        System.out.println("width 0.5pt " + w05);
        System.out.println("width 1.0pt " + w10);
        System.out.println("width 3.0pt " + w30);
        System.out.println("width 8.0pt " + w80);
        float ratio = (float) w05.total() / Math.max(1, w30.total());
        System.out.println("thin/normal geometry ratio = " + ratio);
        assertTrue("a 0.5pt pen emits " + ratio + "x the geometry of a 3pt pen"
                + " (" + w05 + " vs " + w30 + ")", ratio <= 3.0f);
    }

    /** One fast cursive stroke at the given base width, no pressure variation. */
    private static Budget runWidth(float baseWidth) {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.baseWidth = baseWidth;
        preset.minWidth = baseWidth * 0.4f;
        preset.maxWidth = baseWidth * 1.9f;
        // Match the user's configuration: pressure and velocity sensitivity off.
        preset.pressureToWidth = 0f;
        preset.velocityToWidth = 0f;
        BrushEngine engine = new BrushEngine();
        engine.beginStroke(preset);
        LiveOutlineBuilder outline = new LiveOutlineBuilder();
        outline.setPixelsPerPageUnit(TESS_SCALE);
        outline.reset();

        float[] out = new float[4];
        int samples = 240;
        float radius = 9f;
        for (int i = 0; i < samples; i++) {
            float t = i / (float) (samples - 1);
            float theta = t * (float) Math.PI * 6f;
            StrokePoint p = new StrokePoint();
            p.x = 40f + t * 40f + radius * (float) Math.cos(theta);
            p.y = 40f + radius * (float) Math.sin(theta) * 0.6f;
            p.timestampMs = 1000L + (long) (i * (1000f / 240f));
            p.pressure = 0.5f;
            engine.addPoint(p, out);
            outline.addPoint(out[0], out[1], out[2]);
        }
        Budget b = new Budget();
        b.knots = samples;
        b.quads = outline.getQuadCount();
        b.discs = outline.getMidpointCount();
        return b;
    }

    /**
     * The actual "thin pen loses short cursive strokes" defect: how much of a
     * short connecting stroke renders below one device pixel.
     *
     * <p>Nothing here is about speed. The taper floor is a FRACTION of the pen
     * width ({@code TAPER_MIN_FACTOR}), and the start and end tapers multiply.
     * A short stroke — exactly what连笔 writing is made of — sits inside both
     * taper ramps at once, so its width is scaled twice. At 3pt the result is
     * still a couple of pixels wide; at 0.5pt it drops under one pixel and
     * anti-aliases away to nothing, which reads as "the pen didn't register".
     */
    @Test
    public void shortCursiveStrokeStaysVisibleAtTheThinnestPenWidth() {
        float thinFaint = subPixelFraction(0.5f);
        float thickFaint = subPixelFraction(3.0f);
        System.out.println("0.5pt: " + (thinFaint * 100f) + "% of the stroke is sub-pixel");
        System.out.println("3.0pt: " + (thickFaint * 100f) + "% of the stroke is sub-pixel");
        assertTrue("at 0.5pt, " + (thinFaint * 100f) + "% of a short cursive"
                + " stroke renders thinner than one device pixel",
                thinFaint <= 0.10f);
    }

    /**
     * Fraction of a short stroke whose rendered width is under one device
     * pixel at the 1000% zoom cap.
     */
    private static float subPixelFraction(float baseWidth) {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.baseWidth = baseWidth;
        preset.minWidth = baseWidth * 0.4f;
        preset.maxWidth = baseWidth * 1.9f;
        preset.pressureToWidth = 0f;
        preset.velocityToWidth = 0f;

        // A 4 page-unit connecting stroke: a single cursive ligature.
        java.util.List<StrokePoint> pts = new java.util.ArrayList<>();
        int n = 60;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            StrokePoint p = new StrokePoint();
            p.x = 40f + t * 4f;
            p.y = 40f + 0.8f * (float) Math.sin(t * Math.PI);
            p.timestampMs = 1000L + (long) (i * 4);
            p.pressure = 0.5f;
            pts.add(p);
        }
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset, TESS_SCALE);
        float[] widths = mesh.widths;
        int faint = 0;
        for (float w : widths) {
            if (w * TESS_SCALE < 1.0f) {
                faint++;
            }
        }
        return widths.length == 0 ? 0f : (float) faint / widths.length;
    }

    /**
     * How much of its nominal width a SHORT stroke actually keeps.
     *
     * <p>The taper lengths are {@code taper * baseWidth * 10} page units and the
     * start and end ramps multiply. A cursive ligature is only a few page units
     * long, so it can sit inside both ramps over its entire length and never
     * reach full width at all — it is drawn, but as a fraction of the intended
     * thickness. Next to a full-width neighbouring stroke that reads as "the pen
     * didn't write that one", and it is far more obvious at 0.5pt (a couple of
     * pixels) than at 3pt (a dozen).
     */
    @Test
    public void shortStrokeKeepsMostOfItsNominalWidth() {
        for (float len : new float[] {2f, 4f, 8f, 30f}) {
            System.out.println("stroke length " + len + "pu -> mean width "
                    + (meanWidthFraction(len) * 100f) + "% of nominal");
        }
        float shortFrac = meanWidthFraction(3f);
        assertTrue("a 3 page-unit cursive ligature only reaches "
                + (shortFrac * 100f) + "% of the pen's width",
                shortFrac >= 0.5f);
    }

    /** Mean rendered width of a straight stroke, as a fraction of baseWidth. */
    private static float meanWidthFraction(float lengthPu) {
        BrushPreset preset = BrushPreset.fountainPen();
        preset.baseWidth = 0.5f;
        preset.minWidth = 0.2f;
        preset.maxWidth = 0.95f;
        preset.pressureToWidth = 0f;
        preset.velocityToWidth = 0f;
        java.util.List<StrokePoint> pts = new java.util.ArrayList<>();
        int n = 80;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            StrokePoint p = new StrokePoint();
            p.x = 40f + t * lengthPu;
            p.y = 40f;
            p.timestampMs = 1000L + (long) (i * 4);
            p.pressure = 0.5f;
            pts.add(p);
        }
        StrokeMesh mesh = BrushEngine.computeMesh(pts, preset, TESS_SCALE);
        float sum = 0f;
        for (float w : mesh.widths) {
            sum += w;
        }
        return mesh.widths.length == 0 ? 0f
                : (sum / mesh.widths.length) / preset.baseWidth;
    }

    // ------------------------------------------------------------------

    private static final class Budget {
        int knots;
        int quads;
        int discs;

        int total() {
            return quads + discs;
        }

        @Override
        public String toString() {
            return "knots=" + knots + " quads=" + quads + " discs=" + discs;
        }
    }

    /**
     * Feeds one fast, curved, pressure-varying stroke through the real live
     * pipeline (BrushEngine styling + LiveOutlineBuilder tessellation).
     */
    private static Budget run(BrushPreset preset) {
        BrushEngine engine = new BrushEngine();
        engine.beginStroke(preset);
        LiveOutlineBuilder outline = new LiveOutlineBuilder();
        outline.setPixelsPerPageUnit(TESS_SCALE);
        outline.reset();

        Budget b = new Budget();
        float[] out = new float[4];
        float lastX = Float.NaN;
        float lastY = Float.NaN;

        // A looping cursive-like path: ~1200 screen px/s at this zoom, sampled
        // at 240 Hz, with the pressure rise/fall of a real pen stroke.
        int samples = 240;
        float radius = 9f;
        for (int i = 0; i < samples; i++) {
            float t = i / (float) (samples - 1);
            float theta = t * (float) Math.PI * 6f;
            float x = 40f + t * 40f + radius * (float) Math.cos(theta);
            float y = 40f + radius * (float) Math.sin(theta) * 0.6f;

            StrokePoint p = new StrokePoint();
            p.x = x;
            p.y = y;
            p.timestampMs = 1000L + (long) (i * (1000f / 240f));
            // Pressure ramps up, plateaus, falls — a normal stroke.
            p.pressure = 0.15f + 0.75f * (float) Math.sin(Math.PI * t);
            if (!Float.isNaN(lastX)) {
                float dt = 1f / 240f;
                p.velocityX = (x - lastX) / dt;
                p.velocityY = (y - lastY) / dt;
            }
            lastX = x;
            lastY = y;

            engine.addPoint(p, out);
            int before = outline.getQuadCount();
            int discsBefore = outline.getMidpointCount();
            outline.addPoint(out[0], out[1], out[2]);
            if (outline.getQuadCount() != before
                    || outline.getMidpointCount() != discsBefore) {
                b.knots++;
            }
        }
        b.quads = outline.getQuadCount();
        b.discs = outline.getMidpointCount();
        return b;
    }
}
