package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a stream of {@link StrokePoint} samples into a variable-width
 * {@link StrokeMesh} according to a {@link BrushPreset} (requirement 6).
 *
 * <p>Width model per point:
 * <pre>
 *   pVal        = pressureEnabled ? PressureCurve(effectivePressure) : 0.5
 *                 (stroke head pressures are averaged against a 0.25 seed —
 *                  perfect-freehand's anti fat-head trick)
 *   pressureW   = minWidth + (maxWidth - minWidth) * pVal
 *   w           = lerp(baseWidth, pressureW, pressureToWidth)
 *   w          *= velocity factor   (faster = thinner; speed averaged over a
 *                                    40ms trailing window, smoothstep^1.5-mapped)
 *   w          *= tilt factor       (wider when tilted, tiltToWidth)
 *   w          *= nib factor        (calligraphy: direction vs nib angle)
 *   w          *= taper factor      (eased stroke start/end ramps)
 * </pre>
 * Opacity model: preset.opacity scaled down by low pressure
 * (pressureToOpacity) and by tilt (tiltToOpacity, pencil side shading).
 *
 * <p>Mesh hygiene (v1.1.8, from perfect-freehand): pen-lift jitter within
 * ~3px of the stroke tail is dropped, and center points are welded with a
 * width-proportional minimum distance so outline vertex density tracks the
 * stroke width (clean edges, fewer vertices).
 *
 * <p>Pure JVM geometry kernel — no android.graphics imports. The streaming
 * API ({@link #beginStroke}/{@link #addPoint}/{@link #endStroke}) feeds live
 * rendering; {@link #computeMesh(List, BrushPreset)} meshes completed strokes.
 */
public final class BrushEngine {

    /** Pen speed (page units/s) at which the velocity factor fully applies.
     *  v2.0.0: 1500 -> 1300 so real flicks reach full thinning (recorded
     *  handwriting rarely exceeds ~1700 u/s; 1500 left the top unused). */
    private static final float VELOCITY_REF = 1300f;
    /**
     * Velocity sensitivity may be set to 100% in the UI, but a fast sample
     * must never collapse the nib to a near-zero 0.01-unit hairline. That
     * produced apparent missing ink blocks on fast strokes. Keep a physical
     * nib floor while preserving a strong 4.5x fast/slow contrast.
     */
    private static final float VELOCITY_MIN_FACTOR = 0.22f;
    /** Trailing window used to average pen speed (hides per-sample jitter).
     *  v2.2.2: 40 -> 30ms — the width follows a speed change ~10ms sooner
     *  (fast strokes reach their thinned width before the segment ends)
     *  while still averaging ~7 stylus samples at 240Hz. */
    private static final long VELOCITY_WINDOW_MS = 30L;
    /** Tilt (radians) treated as "fully tilted" (AXIS_TILT range is 0..PI/2). */
    private static final float MAX_TILT = (float) (Math.PI / 2.0);
    /** Fixed calligraphy nib angle used when the point has no orientation. */
    private static final float DEFAULT_NIB_ANGLE = (float) (Math.PI / 4.0);
    /** Narrowest nib cross-section as a fraction of width (calligraphy). */
    private static final float NIB_MIN_FACTOR = 0.35f;
    /** How strongly the nib factor modulates calligraphy width. */
    private static final float NIB_STRENGTH = 0.8f;
    /** Taper lengths are this multiple of baseWidth at taper == 1. */
    private static final float TAPER_LENGTH_MULTIPLE = 10f;
    /** Width floor at the very tip of a taper (fraction of full width). */
    private static final float TAPER_MIN_FACTOR = 0.05f;
    /** Each taper ramp may cover at most this fraction of the stroke length. */
    private static final float MAX_TAPER_FRACTION = 0.35f;
    /** Ink is never rendered thinner than this many device pixels. Package
     *  visible so {@link LiveOutlineBuilder} applies the same floor live. */
    static final float MIN_VISIBLE_WIDTH_PX = 1.0f;
    /** Absolute weld floor for centerline points (avoids zero normals). */
    private static final float WELD_DISTANCE = 0.05f;
    /** Center weld distance also scales with local width (outline dedup). */
    private static final float WELD_WIDTH_FACTOR = 0.5f;
    /**
     * v2.4.0 upper bound on the weld distance, in final device pixels — the
     * commit-time twin of {@code LiveOutlineBuilder.MAX_WELD_PX}.
     *
     * <p>The page-unit weld above discards anything the user drew finer than
     * {@code 0.5 * width}. When they zoom in to write something small, the
     * features they are drawing *are* small in page units, so that rule
     * quantises exactly the detail they zoomed in for. Clamping it in device
     * pixels keeps the mesh as detailed as the output it is being meshed for,
     * and — since the live builder applies the same clamp — keeps the live
     * stroke and the committed one identical, so nothing shifts on pen-up.
     *
     * <p>Renders at low zoom are unaffected: there the page-unit rule is
     * already the smaller of the two.
     */
    private static final float MAX_WELD_PX = 1.5f;
    /** Non-final points this close to the stroke end are pen-lift noise. */
    private static final float END_NOISE_DISTANCE = 2f;
    /** Stroke-head samples whose pressure is averaged (anti fat-head). */
    private static final int HEAD_PRESSURE_POINTS = 8;
    /** Seed pressure for the head average (perfect-freehand FIRST_PRESSURE). */
    private static final float HEAD_PRESSURE_SEED = 0.25f;

    private BrushPreset preset;
    private final List<StrokePoint> points = new ArrayList<>();
    private final List<float[]> styled = new ArrayList<>(); // {x, y, width, opacity}
    /**
     * Running arc length from the stroke start for the streaming start taper
     * (v2.1.2). Recomputing the sum for every sample was O(n²): at 240 Hz a
     * long fountain-pen stroke re-walked the whole point list per sample,
     * which read as growing input lag the longer the stroke got (ballpoint
     * and pencil have taperStart = 0 and were unaffected). */
    private float liveDistanceFromStart;

    // ------------------------------------------------------------------
    // Streaming API (live stroke)
    // ------------------------------------------------------------------

    /** Starts a new stroke with the given preset snapshot. */
    public void beginStroke(BrushPreset preset) {
        this.preset = preset;
        points.clear();
        styled.clear();
        liveDistanceFromStart = 0f;
    }

    /**
     * Adds one (already smoothed) sample; returns its {x, y, width, opacity}
     * in the given out array. Start taper is applied immediately; end taper is
     * applied retroactively by {@link #endStroke()} / {@link #computeMesh}.
     */
    public float[] addPoint(StrokePoint p, float[] out) {
        if (preset == null) {
            throw new IllegalStateException("beginStroke() not called");
        }
        points.add(p);
        if (out == null || out.length < 4) {
            out = new float[4];
        }
        float width = computeWidth(points.size() - 1, p, preset, points);
        float opacity = computeOpacity(points.size() - 1, p, preset, points);
        // Live start taper: running arc length from stroke start (O(1) per
        // sample; the old per-sample re-walk was O(n²), see the field doc).
        if (preset.taperStart > 0f) {
            int idx = points.size() - 1;
            if (idx > 0) {
                StrokePoint prev = points.get(idx - 1);
                liveDistanceFromStart += dist(prev, p);
            }
            float len = preset.taperStart * preset.baseWidth * TAPER_LENGTH_MULTIPLE;
            width *= taperFactorStart(liveDistanceFromStart, len);
        }
        out[0] = p.x;
        out[1] = p.y;
        out[2] = width;
        out[3] = opacity;
        styled.add(new float[]{out[0], out[1], out[2], out[3]});
        return out;
    }

    /** Applies the end taper and meshes the collected points. */
    public StrokeMesh endStroke() {
        StrokeMesh mesh = computeMesh(points, preset);
        points.clear();
        styled.clear();
        preset = null;
        return mesh;
    }

    /** Styled points collected so far: each element is {x, y, width, opacity}. */
    public List<float[]> getStyledPoints() {
        return styled;
    }

    // ------------------------------------------------------------------
    // One-shot meshing (completed strokes, rendering)
    // ------------------------------------------------------------------

    /** Meshes a full point list with both tapers applied. */
    public static StrokeMesh computeMesh(List<StrokePoint> pts, BrushPreset preset) {
        return computeMesh(pts, preset, 1f);
    }

    /**
     * Meshes at a quality appropriate for the final device-pixel scale.
     * Geometry remains in page coordinates; only spline tessellation density
     * changes, so serialized strokes stay zoom-independent.
     */
    public static StrokeMesh computeMesh(List<StrokePoint> pts, BrushPreset preset,
                                         float pixelsPerPageUnit) {
        int n = pts.size();
        if (n == 0) {
            return new StrokeMesh(new float[0], new float[0], new float[0],
                    new float[0], new float[0], preset.color, new float[4]);
        }
        // Style every point (no tapers yet).
        float[] center = new float[n * 2];
        float[] widths = new float[n];
        float[] opacities = new float[n];
        float[] distFromStart = new float[n];
        for (int i = 0; i < n; i++) {
            StrokePoint p = pts.get(i);
            center[i * 2] = p.x;
            center[i * 2 + 1] = p.y;
            widths[i] = computeWidth(i, p, preset, pts);
            opacities[i] = computeOpacity(i, p, preset, pts);
            distFromStart[i] = i == 0 ? 0f
                    : distFromStart[i - 1] + dist(pts.get(i - 1), p);
        }
        // Tapers (start + end) on the full arc length.
        float totalLength = distFromStart[n - 1];
        float startLen = preset.taperStart * preset.baseWidth * TAPER_LENGTH_MULTIPLE;
        float endLen = preset.taperEnd * preset.baseWidth * TAPER_LENGTH_MULTIPLE;
        // v3.2.0: a taper may never consume the whole stroke.
        //
        // The lengths above depend only on the preset, never on how long the
        // stroke actually is. The fountain pen's two ramps come to 3.25 page
        // units — LONGER than a cursive ligature — so a short connecting stroke
        // sat inside both ramps over its entire length and the two factors
        // multiplied: a 2 page-unit stroke rendered at 16% of the pen's nominal
        // width. It was drawn, just as a hairline beside full-width neighbours,
        // which reads as the pen having skipped that stroke. At 0.5pt that
        // hairline is about one device pixel; at 3pt the same 16% is still ~6px,
        // which is exactly why only the thinnest setting looked broken.
        // Capping each ramp at a fraction of the real stroke length keeps the
        // fountain-pen character on normal strokes while guaranteeing every
        // stroke has a full-width middle.
        float maxTaperLen = totalLength * MAX_TAPER_FRACTION;
        startLen = Math.min(startLen, maxTaperLen);
        endLen = Math.min(endLen, maxTaperLen);
        // v3.2.0 visible-tip floor. TAPER_MIN_FACTOR is a FRACTION of the pen
        // width, and the two tapers multiply, so a short stroke sits inside
        // both ramps at once. At 3pt the tapered tip is still a couple of
        // pixels wide; at the thinnest 0.5pt setting it drops to 0.025 page
        // units — under a third of a device pixel — and anti-aliases away to
        // nothing. Cursive writing is made of exactly those short strokes, so
        // the thinnest pen appeared to drop connecting strokes entirely while a
        // slightly thicker one was fine. Never let ink render thinner than it
        // can be seen.
        float minVisibleWidth = pixelsPerPageUnit > 0f
                ? MIN_VISIBLE_WIDTH_PX / pixelsPerPageUnit : 0f;
        for (int i = 0; i < n; i++) {
            if (startLen > 0f) {
                widths[i] *= taperFactorStart(distFromStart[i], startLen);
            }
            if (endLen > 0f) {
                widths[i] *= taperFactorEnd(totalLength - distFromStart[i], endLen);
            }
            // Only ever raises a width that would be invisible; a stroke the
            // user set thinner than one pixel still renders at its own width
            // when that is already above the floor.
            if (widths[i] < minVisibleWidth) {
                widths[i] = Math.min(minVisibleWidth, preset.baseWidth);
            }
        }
        return buildMesh(center, widths, opacities, preset.color, n,
                distFromStart, totalLength, pixelsPerPageUnit);
    }

    // ------------------------------------------------------------------
    // Width / opacity model
    // ------------------------------------------------------------------

    private static float computeWidth(int index, StrokePoint p, BrushPreset preset,
                                      List<StrokePoint> pts) {
        float pVal = preset.pressureEnabled
                ? PressureCurve.evalForPreset(preset, effectivePressure(index, pts))
                : 0.5f;
        float pressureWidth = preset.minWidth + (preset.maxWidth - preset.minWidth) * pVal;
        float w = lerp(preset.baseWidth, pressureWidth, clamp01(preset.pressureToWidth));

        // Velocity: faster = thinner. Speed is averaged over a short trailing
        // window (pen sampling is jittery) and mapped through a saturating
        // curve so normal handwriting keeps full width while flicks slim down.
        // v2.0.0: smoothstep -> smoothstep^1.5 — endpoints (0 and full
        // thinning at VELOCITY_REF) unchanged, but the mid range (ordinary
        // fast writing ~400-800 u/s) loses much less width; thinning is
        // concentrated in true flicks.
        if (preset.velocityToWidth > 0f) {
            float speed = smoothedSpeed(index, pts);
            float t = clamp01(speed / VELOCITY_REF);
            float ss = t * t * (3f - 2f * t);
            float vNorm = (float) Math.pow(ss, 1.5f);
            float velocityFactor = 1f - clamp01(preset.velocityToWidth) * vNorm;
            w *= Math.max(VELOCITY_MIN_FACTOR, velocityFactor);
        }
        // Tilt: leaning the pen widens the mark (pencil side shading).
        float tiltNorm = clamp01(p.tilt / MAX_TILT);
        if (preset.tiltEnabled && preset.tiltToWidth > 0f) {
            w *= 1f + clamp01(preset.tiltToWidth) * tiltNorm;
        }
        // Calligraphy nib: width depends on stroke direction vs nib angle.
        if (preset.brushType == BrushPreset.BrushType.CALLIGRAPHY_PEN) {
            float dir = strokeDirection(index, pts);
            if (!Float.isNaN(dir)) {
                float nibAngle = p.orientation != 0f ? p.orientation : DEFAULT_NIB_ANGLE;
                float align = Math.abs((float) Math.sin(dir - nibAngle));
                float nibFactor = NIB_MIN_FACTOR + (1f - NIB_MIN_FACTOR) * align;
                w *= lerp(1f, nibFactor, NIB_STRENGTH);
            }
        }
        return Math.max(0.01f, w);
    }

    private static float computeOpacity(int index, StrokePoint p, BrushPreset preset,
                                        List<StrokePoint> pts) {
        float o = preset.opacity;
        if (preset.pressureToOpacity > 0f) {
            float pVal = preset.pressureEnabled
                    ? PressureCurve.evalForPreset(preset, effectivePressure(index, pts))
                    : 0.5f;
            o *= lerp(1f, Math.max(0.15f, pVal), clamp01(preset.pressureToOpacity));
        }
        if (preset.tiltEnabled && preset.tiltToOpacity > 0f) {
            float tiltNorm = clamp01(p.tilt / MAX_TILT);
            o *= 1f - clamp01(preset.tiltToOpacity) * tiltNorm * 0.6f;
        }
        return clamp01(o);
    }

    // ------------------------------------------------------------------
    // Meshing
    // ------------------------------------------------------------------

    /**
     * Weld radius for one centerline point: the width-proportional page-unit
     * rule, clamped so it never exceeds {@link #MAX_WELD_PX} in the device
     * pixels this mesh is being built for. Mirrors
     * {@code LiveOutlineBuilder.weldDistance} so live and committed geometry
     * keep the same knots.
     */
    private static float weldDistance(float width, float pixelsPerPageUnit) {
        float pageRule = Math.max(WELD_DISTANCE, width * WELD_WIDTH_FACTOR);
        float scale = Float.isFinite(pixelsPerPageUnit) && pixelsPerPageUnit > 1f
                ? pixelsPerPageUnit : 1f;
        return Math.max(WELD_DISTANCE, Math.min(pageRule, MAX_WELD_PX / scale));
    }

    private static StrokeMesh buildMesh(float[] centerAll, float[] widthsAll,
                                        float[] opacitiesAll, int color, int n,
                                        float[] distFromStart, float totalLength,
                                        float pixelsPerPageUnit) {
        // Weld center points with a width-proportional minimum distance
        // (perfect-freehand outline dedup): vertex density tracks the stroke
        // width, so edges stay clean and cheap. The temp arrays carry one
        // extra slot for the single-tap dot duplication.
        float[] centerTmp = new float[(n + 1) * 2];
        float[] widthsTmp = new float[n + 1];
        float[] opacitiesTmp = new float[n + 1];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && i < n - 1) {
                // Pen-lift noise: non-final points right at the stroke tail
                // would add spurious spikes; drop them.
                //
                // v3.2.0: this window is a fixed 2 page units, which is longer
                // than an entire cursive ligature. On a 2 page-unit stroke it
                // discarded EVERY intermediate sample, leaving just the two
                // endpoints — and both endpoints sit at the taper minimum, so
                // the whole stroke rendered at ~16% of the pen's width. Scaling
                // the window down for short strokes keeps its purpose (trimming
                // the last few samples of pen-lift wobble) without letting it
                // consume the stroke.
                if (totalLength - distFromStart[i]
                        < Math.min(END_NOISE_DISTANCE, totalLength * 0.15f)) {
                    continue;
                }
                float dx = centerAll[i * 2] - centerTmp[(m - 1) * 2];
                float dy = centerAll[i * 2 + 1] - centerTmp[(m - 1) * 2 + 1];
                float minDist = weldDistance(widthsAll[i], pixelsPerPageUnit);
                if (dx * dx + dy * dy < minDist * minDist) {
                    continue;
                }
            }
            centerTmp[m * 2] = centerAll[i * 2];
            centerTmp[m * 2 + 1] = centerAll[i * 2 + 1];
            widthsTmp[m] = widthsAll[i];
            opacitiesTmp[m] = opacitiesAll[i];
            m++;
        }
        if (m == 1) {
            // Degenerate single tap: duplicate the point so a dot can render.
            centerTmp[2] = centerTmp[0];
            centerTmp[3] = centerTmp[1];
            widthsTmp[1] = widthsTmp[0];
            opacitiesTmp[1] = opacitiesTmp[0];
            m = 2;
        }

        float[] center = new float[m * 2];
        float[] widths = new float[m];
        float[] opacities = new float[m];
        System.arraycopy(centerTmp, 0, center, 0, m * 2);
        System.arraycopy(widthsTmp, 0, widths, 0, m);
        System.arraycopy(opacitiesTmp, 0, opacities, 0, m);

        // v1.4.18: Catmull-Rom refine the welded polyline so bends render as
        // smooth curves instead of polygon chords (straight runs pass through
        // unsubdivided). Knot normals are unchanged (central difference), so
        // the live outline and this committed mesh agree at every sample.
        SplineRefiner.Refined r = SplineRefiner.refine(
                center, widths, opacities, m, pixelsPerPageUnit);
        int rn = r.count;

        float[] left = new float[rn * 2];
        float[] right = new float[rn * 2];
        float bl = Float.MAX_VALUE, bt = Float.MAX_VALUE;
        float br = -Float.MAX_VALUE, bb = -Float.MAX_VALUE;
        for (int i = 0; i < rn; i++) {
            float nx = r.normals[i * 2];
            float ny = r.normals[i * 2 + 1];
            float half = r.widths[i] / 2f;
            float cx = r.center[i * 2];
            float cy = r.center[i * 2 + 1];
            left[i * 2] = cx + nx * half;
            left[i * 2 + 1] = cy + ny * half;
            right[i * 2] = cx - nx * half;
            right[i * 2 + 1] = cy - ny * half;
            bl = Math.min(bl, Math.min(left[i * 2], right[i * 2]));
            bt = Math.min(bt, Math.min(left[i * 2 + 1], right[i * 2 + 1]));
            br = Math.max(br, Math.max(left[i * 2], right[i * 2]));
            bb = Math.max(bb, Math.max(left[i * 2 + 1], right[i * 2 + 1]));
        }
        return new StrokeMesh(r.center, left, right, r.widths, r.opacities,
                color, new float[]{bl, bt, br, bb});
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Start taper: easeOutQuad (t*(2-t)) — ink lands immediately, then eases
     *  into full width (perfect-freehand default; a slow t² head feels blunt). */
    private static float taperFactorStart(float distance, float taperLength) {
        if (taperLength <= 0f) {
            return 1f;
        }
        float t = clamp01(distance / taperLength);
        float eased = t * (2f - t);
        return TAPER_MIN_FACTOR + (1f - TAPER_MIN_FACTOR) * eased;
    }

    /** End taper: ease-out cubic, so the line releases quickly on nib lift. */
    private static float taperFactorEnd(float distanceToEnd, float taperLength) {
        if (taperLength <= 0f) {
            return 1f;
        }
        float t = clamp01(distanceToEnd / taperLength);
        float inv = 1f - t;
        float eased = 1f - inv * inv * inv;
        return TAPER_MIN_FACTOR + (1f - TAPER_MIN_FACTOR) * eased;
    }

    /**
     * Pressure used for width/opacity at a stroke point. The first few
     * samples of a stroke are averaged against a 0.25 seed (perfect-freehand):
     * touch-down is slow and heavy, and without this the stroke head balloons.
     */
    private static float effectivePressure(int index, List<StrokePoint> pts) {
        if (index >= HEAD_PRESSURE_POINTS) {
            return pts.get(index).pressure;
        }
        float acc = HEAD_PRESSURE_SEED;
        for (int i = 0; i <= index; i++) {
            acc = (acc + pts.get(i).pressure) / 2f;
        }
        return acc;
    }

    /**
     * Averages pen speed over a trailing {@link #VELOCITY_WINDOW_MS} window.
     * Per-sample velocity from the input pipeline is jittery; a short window
     * keeps the width response stable without adding noticeable lag.
     */
    private static float smoothedSpeed(int index, List<StrokePoint> pts) {
        long t0 = pts.get(index).timestampMs;
        double sum = 0.0;
        int count = 0;
        for (int i = index; i >= 0; i--) {
            StrokePoint p = pts.get(i);
            if (i < index && t0 - p.timestampMs > VELOCITY_WINDOW_MS) {
                break;
            }
            sum += Math.hypot(p.velocityX, p.velocityY);
            count++;
        }
        return (float) (sum / count);
    }

    /** Direction of travel at point i, or NaN for a degenerate neighborhood. */
    private static float strokeDirection(int i, List<StrokePoint> pts) {
        int prev = Math.max(0, i - 1);
        int next = Math.min(pts.size() - 1, i + 1);
        float dx = pts.get(next).x - pts.get(prev).x;
        float dy = pts.get(next).y - pts.get(prev).y;
        if (dx * dx + dy * dy < 1e-8f) {
            return Float.NaN;
        }
        return (float) Math.atan2(dy, dx);
    }

    private static float dist(StrokePoint a, StrokePoint b) {
        return (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
