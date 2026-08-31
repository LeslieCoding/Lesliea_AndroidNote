package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.common.Geometry;
import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Vector eraser (requirement 6.6). Never rasterizes the page: PIXEL mode cuts
 * the strokes the eraser circle swept over into sub-strokes, STROKE mode
 * deletes whole strokes on touch, AREA mode cuts/deletes by rectangle or
 * lasso polygon. {@link TypeFilter} restricts erasing to the ink or the
 * highlight layer.
 *
 * <p>Pure JVM — works on {@link Stroke#computeBounds()} and {@link Geometry}
 * primitives so every path is unit-testable off-device.
 */
public final class EraserEngine {

    public enum Mode {PIXEL, STROKE, AREA}

    public enum TypeFilter {ALL, INK_ONLY, HIGHLIGHT_ONLY}

    /** Outcome of one erase gesture. */
    public static final class EraseResult {
        /** Strokes still on the page (originals untouched or cut pieces). */
        public final List<Stroke> remaining = new ArrayList<>();
        /** Strokes fully or partially erased (for undo / dirty-rect tracking). */
        public final List<Stroke> removed = new ArrayList<>();

        public boolean changed() {
            return !removed.isEmpty();
        }
    }

    /** Classifies a page-space point as erased or kept. */
    private interface ErasedPredicate {
        boolean test(float x, float y);
    }

    /** Half-width approximation of a stroke, used as hit padding. */
    private static float halfWidth(Stroke s) {
        return s.brushPreset != null ? s.brushPreset.baseWidth / 2f : 1.5f;
    }

    private static boolean accepts(Stroke s, TypeFilter filter) {
        switch (filter) {
            case INK_ONLY:
                return s.layer == Stroke.Layer.INK;
            case HIGHLIGHT_ONLY:
                return s.layer == Stroke.Layer.HIGHLIGHT;
            case ALL:
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------
    // Eraser-path modes (PIXEL / STROKE)
    // ------------------------------------------------------------------

    /**
     * Applies an eraser swipe.
     *
     * @param strokes    page strokes (not mutated)
     * @param eraserPath flat {x,y,...} samples of the eraser motion
     * @param radius     eraser radius in page units
     */
    public EraseResult erase(List<Stroke> strokes, float[] eraserPath,
                             float radius, Mode mode, TypeFilter filter) {
        EraseResult result = new EraseResult();
        for (Stroke stroke : strokes) {
            if (!accepts(stroke, filter) || stroke.points.isEmpty()) {
                result.remaining.add(stroke);
                continue;
            }
            if (mode == Mode.STROKE) {
                if (touchesStroke(stroke, eraserPath, radius)) {
                    result.removed.add(stroke);
                } else {
                    result.remaining.add(stroke);
                }
                continue;
            }
            // PIXEL: cut by hit intervals.
            float hitDistance = radius + halfWidth(stroke);
            ErasedPredicate predicate =
                    (x, y) -> nearPath(x, y, eraserPath, hitDistance);
            applyCut(stroke, predicate, result);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // AREA mode (rectangle / lasso polygon)
    // ------------------------------------------------------------------

    /**
     * Applies an area erase. Strokes fully outside survive untouched; strokes
     * partially inside are cut at the region boundary; strokes fully inside
     * are removed.
     *
     * @param polygon region as flat vertices {x0,y0,...} (a rect is a
     *                4-vertex polygon), implicitly closed
     */
    public EraseResult eraseArea(List<Stroke> strokes, float[] polygon, TypeFilter filter) {
        EraseResult result = new EraseResult();
        int vertexCount = polygon.length / 2;
        for (Stroke stroke : strokes) {
            if (!accepts(stroke, filter) || stroke.points.isEmpty()) {
                result.remaining.add(stroke);
                continue;
            }
            ErasedPredicate predicate =
                    (x, y) -> Geometry.pointInPolygon(polygon, vertexCount, x, y);
            applyCut(stroke, predicate, result);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Cutting
    // ------------------------------------------------------------------

    /** Evaluates the predicate per point and cuts the stroke if it was hit. */
    private static void applyCut(Stroke stroke, ErasedPredicate predicate, EraseResult result) {
        List<StrokePoint> pts = stroke.points;
        boolean[] erased = new boolean[pts.size()];
        boolean any = false;
        boolean all = true;
        for (int i = 0; i < pts.size(); i++) {
            StrokePoint p = pts.get(i);
            erased[i] = predicate.test(p.x, p.y);
            any |= erased[i];
            all &= erased[i];
        }
        if (!any) {
            result.remaining.add(stroke);
            return;
        }
        result.removed.add(stroke);
        if (all) {
            return; // fully erased
        }

        List<StrokePoint> run = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            StrokePoint p = pts.get(i);
            if (!erased[i]) {
                // Entering a kept run from an erased span: add boundary cut.
                if (i > 0 && erased[i - 1]) {
                    run.add(interpolateBoundary(pts.get(i - 1), p, predicate));
                }
                run.add(copyPoint(p));
            } else if (!run.isEmpty()) {
                // Leaving a kept run: close it with the boundary cut.
                run.add(interpolateBoundary(pts.get(i - 1), p, predicate));
                emitRun(stroke, run, result);
                run = new ArrayList<>();
            }
        }
        emitRun(stroke, run, result);
    }

    /** A kept run becomes a new stroke when it has at least two points. */
    private static void emitRun(Stroke source, List<StrokePoint> run, EraseResult result) {
        if (run.size() < 2) {
            run.clear();
            return;
        }
        Stroke piece = new Stroke();
        piece.id = IdGenerator.newId();
        piece.brushPresetId = source.brushPresetId;
        piece.brushPreset = source.brushPreset;
        piece.color = source.color;
        piece.zIndex = source.zIndex;
        piece.createdAtMs = source.createdAtMs;
        piece.layer = source.layer;
        // Audio anchors only make sense for the original stroke.
        piece.strokeStartAudioTimeMs = null;
        piece.strokeEndAudioTimeMs = null;
        piece.points = new ArrayList<>(run);
        result.remaining.add(piece);
        run.clear();
    }

    /**
     * Finds where segment (a,b) crosses the erased/kept boundary via binary
     * search on the real predicate, then interpolates every point field.
     */
    private static StrokePoint interpolateBoundary(StrokePoint a, StrokePoint b,
                                                   ErasedPredicate predicate) {
        float t0 = 0f; // state of a
        float t1 = 1f; // state of b
        for (int iter = 0; iter < 12; iter++) {
            float mid = (t0 + t1) / 2f;
            float x = a.x + mid * (b.x - a.x);
            float y = a.y + mid * (b.y - a.y);
            // Keep the bracket between the two differing endpoint states.
            if (predicate.test(x, y) == predicate.test(a.x, a.y)) {
                t0 = mid;
            } else {
                t1 = mid;
            }
        }
        float t = (t0 + t1) / 2f;
        StrokePoint cut = new StrokePoint();
        cut.x = a.x + t * (b.x - a.x);
        cut.y = a.y + t * (b.y - a.y);
        cut.timestampMs = a.timestampMs + (long) (t * (b.timestampMs - a.timestampMs));
        cut.pressure = a.pressure + t * (b.pressure - a.pressure);
        cut.tilt = a.tilt + t * (b.tilt - a.tilt);
        cut.orientation = a.orientation + t * (b.orientation - a.orientation);
        cut.toolType = a.toolType;
        cut.pointerId = a.pointerId;
        cut.velocityX = a.velocityX + t * (b.velocityX - a.velocityX);
        cut.velocityY = a.velocityY + t * (b.velocityY - a.velocityY);
        return cut;
    }

    private static boolean touchesStroke(Stroke stroke, float[] eraserPath, float radius) {
        float hit = radius + halfWidth(stroke);
        // Coarse filter on bounds.
        float[] b = stroke.computeBounds();
        float[] eb = pathBounds(eraserPath);
        if (b[2] + hit < eb[0] || b[0] - hit > eb[2]
                || b[3] + hit < eb[1] || b[1] - hit > eb[3]) {
            return false;
        }
        for (StrokePoint p : stroke.points) {
            if (nearPath(p.x, p.y, eraserPath, hit)) {
                return true;
            }
        }
        return false;
    }

    /** Distance from (x,y) to the eraser polyline is within threshold. */
    private static boolean nearPath(float x, float y, float[] path, float threshold) {
        int n = path.length / 2;
        if (n == 0) {
            return false;
        }
        if (n == 1) {
            float dx = x - path[0];
            float dy = y - path[1];
            return dx * dx + dy * dy <= threshold * threshold;
        }
        for (int i = 0; i + 1 < n; i++) {
            float d = Geometry.distancePointToSegment(x, y,
                    path[i * 2], path[i * 2 + 1],
                    path[i * 2 + 2], path[i * 2 + 3]);
            if (d <= threshold) {
                return true;
            }
        }
        return false;
    }

    private static float[] pathBounds(float[] path) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < path.length; i += 2) {
            l = Math.min(l, path[i]);
            t = Math.min(t, path[i + 1]);
            r = Math.max(r, path[i]);
            b = Math.max(b, path[i + 1]);
        }
        return new float[]{l, t, r, b};
    }

    private static StrokePoint copyPoint(StrokePoint p) {
        StrokePoint c = new StrokePoint();
        c.x = p.x;
        c.y = p.y;
        c.timestampMs = p.timestampMs;
        c.pressure = p.pressure;
        c.tilt = p.tilt;
        c.orientation = p.orientation;
        c.toolType = p.toolType;
        c.pointerId = p.pointerId;
        c.velocityX = p.velocityX;
        c.velocityY = p.velocityY;
        return c;
    }
}
