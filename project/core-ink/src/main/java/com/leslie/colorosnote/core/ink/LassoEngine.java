package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.common.Geometry;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lasso / rectangular selection hit-testing (requirement 16). Two-phase:
 * axis-aligned bounds as a coarse filter, then point-level precise tests
 * (stroke sample points, segment/edge intersections, transformed object
 * corners). Type filters cover ink / highlight / image / text / shape /
 * sticker / tape.
 *
 * <p>Pure JVM — regions and bounds are flat float arrays, object transforms
 * are the 9-float matrix values stored on {@link PageObject}.
 */
public final class LassoEngine {

    public enum Mode {FREE, RECT}

    /** Selectable categories; maps stroke layers and object types. */
    public enum TypeFilter {INK, HIGHLIGHT, IMAGE, TEXT, SHAPE, STICKER, TAPE}

    /** Everything selectable. */
    public static final Set<TypeFilter> ALL_TYPES =
            EnumSet.allOf(TypeFilter.class);

    /** Result of a selection query. */
    public static final class Selection {
        public final List<Stroke> strokes = new ArrayList<>();
        public final List<PageObject> objects = new ArrayList<>();

        public boolean isEmpty() {
            return strokes.isEmpty() && objects.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Selects strokes/objects of the page inside the region.
     *
     * @param region FREE: flat polygon vertices {x0,y0,...} (implicitly
     *               closed); RECT: {left, top, right, bottom}
     * @param filters categories to include
     */
    public Selection select(Page page, float[] region, Mode mode, Set<TypeFilter> filters) {
        return select(page.strokes, page.objects, region, mode, filters);
    }

    /**
     * Tap-to-select (v1.4.6): single-object Selection with the topmost
     * (highest zIndex) unlocked object whose transformed bounds contain the
     * page point. {@code slop} pads the hit area (page units); locked objects
     * are skipped just like in {@link #select}. Empty when nothing is hit.
     */
    public Selection selectAt(Page page, float x, float y, float slop) {
        Selection selection = new Selection();
        PageObject best = null;
        for (PageObject object : page.objects) {
            if (!acceptsObject(object, ALL_TYPES)) {
                continue;
            }
            float[] corners = worldCorners(object);
            boolean hit = Geometry.pointInPolygon(corners, 4, x, y);
            if (!hit && slop > 0f) {
                float[] aabb = boundsOf(corners);
                hit = x >= aabb[0] - slop && x <= aabb[2] + slop
                        && y >= aabb[1] - slop && y <= aabb[3] + slop;
            }
            if (hit && (best == null || object.zIndex > best.zIndex)) {
                best = object;
            }
        }
        if (best != null) {
            selection.objects.add(best);
        }
        return selection;
    }

    /** Same as {@link #select(Page, float[], Mode, Set)} on explicit lists. */
    public Selection select(List<Stroke> strokes, List<PageObject> objects,
                            float[] region, Mode mode, Set<TypeFilter> filters) {
        Selection selection = new Selection();
        float[] polygon = toPolygon(region, mode);
        int vertexCount = polygon.length / 2;
        if (vertexCount < 3) {
            return selection;
        }
        float[] regionBounds = boundsOf(polygon);

        if (filters.contains(TypeFilter.INK) || filters.contains(TypeFilter.HIGHLIGHT)) {
            for (Stroke stroke : strokes) {
                if (!acceptsStroke(stroke, filters)) {
                    continue;
                }
                if (!boundsIntersect(stroke.computeBounds(), regionBounds)) {
                    continue;
                }
                if (strokeInRegion(stroke, polygon, vertexCount)) {
                    selection.strokes.add(stroke);
                }
            }
        }
        for (PageObject object : objects) {
            if (!acceptsObject(object, filters)) {
                continue;
            }
            float[] aabb = worldAabb(object);
            if (!boundsIntersect(aabb, regionBounds)) {
                continue;
            }
            if (objectInRegion(object, aabb, polygon, vertexCount)) {
                selection.objects.add(object);
            }
        }
        return selection;
    }

    // ------------------------------------------------------------------
    // Stroke tests
    // ------------------------------------------------------------------

    private static boolean acceptsStroke(Stroke stroke, Set<TypeFilter> filters) {
        return stroke.layer == Stroke.Layer.HIGHLIGHT
                ? filters.contains(TypeFilter.HIGHLIGHT)
                : filters.contains(TypeFilter.INK);
    }

    private static boolean strokeInRegion(Stroke stroke, float[] polygon, int vertexCount) {
        List<StrokePoint> pts = stroke.points;
        for (StrokePoint p : pts) {
            if (Geometry.pointInPolygon(polygon, vertexCount, p.x, p.y)) {
                return true;
            }
        }
        // Catch long segments that cross the region without a sample inside.
        for (int i = 0; i + 1 < pts.size(); i++) {
            StrokePoint a = pts.get(i);
            StrokePoint b = pts.get(i + 1);
            if (segmentCrossesPolygon(a.x, a.y, b.x, b.y, polygon, vertexCount)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Object tests
    // ------------------------------------------------------------------

    private static boolean acceptsObject(PageObject object, Set<TypeFilter> filters) {
        // Locked objects are never selectable (v1.4.6): they must not be
        // lassoed / tap-selected into a deletable, movable selection.
        if (object.locked || object.objectType == null) {
            return false;
        }
        switch (object.objectType) {
            case IMAGE:
                return filters.contains(TypeFilter.IMAGE);
            case TEXT_BOX:
                return filters.contains(TypeFilter.TEXT);
            case SHAPE:
                return filters.contains(TypeFilter.SHAPE);
            case STICKER:
                return filters.contains(TypeFilter.STICKER);
            case TAPE:
                return filters.contains(TypeFilter.TAPE);
            default:
                return false;
        }
    }

    /**
     * Precise object test: any transformed corner inside the region, any
     * region vertex inside the object's world AABB, or crossing edges.
     */
    private static boolean objectInRegion(PageObject object, float[] aabb,
                                          float[] polygon, int vertexCount) {
        float[] corners = worldCorners(object);
        for (int i = 0; i < 4; i++) {
            if (Geometry.pointInPolygon(polygon, vertexCount,
                    corners[i * 2], corners[i * 2 + 1])) {
                return true;
            }
        }
        for (int i = 0; i < vertexCount; i++) {
            float x = polygon[i * 2];
            float y = polygon[i * 2 + 1];
            if (x >= aabb[0] && x <= aabb[2] && y >= aabb[1] && y <= aabb[3]) {
                return true;
            }
        }
        // Edge crossing between the AABB outline and the region polygon.
        float[] rect = new float[]{
                aabb[0], aabb[1], aabb[2], aabb[1],
                aabb[2], aabb[3], aabb[0], aabb[3]};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            if (segmentCrossesPolygon(rect[i * 2], rect[i * 2 + 1],
                    rect[j * 2], rect[j * 2 + 1], polygon, vertexCount)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    private static float[] toPolygon(float[] region, Mode mode) {
        if (mode == Mode.FREE) {
            return region;
        }
        // RECT: {left, top, right, bottom} -> 4 vertices.
        float l = Math.min(region[0], region[2]);
        float t = Math.min(region[1], region[3]);
        float r = Math.max(region[0], region[2]);
        float b = Math.max(region[1], region[3]);
        return new float[]{l, t, r, t, r, b, l, b};
    }

    private static boolean segmentCrossesPolygon(float ax, float ay, float bx, float by,
                                                 float[] polygon, int vertexCount) {
        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            if (Geometry.segmentsIntersect(ax, ay, bx, by,
                    polygon[j * 2], polygon[j * 2 + 1],
                    polygon[i * 2], polygon[i * 2 + 1])) {
                return true;
            }
        }
        return false;
    }

    /** Transformed corners of the object's local bounds (affine part only). */
    private static float[] worldCorners(PageObject object) {
        float[] m = object.transform;
        float[] b = object.bounds;
        float[] corners = new float[8];
        float[] xs = {b[0], b[2], b[2], b[0]};
        float[] ys = {b[1], b[1], b[3], b[3]};
        for (int i = 0; i < 4; i++) {
            corners[i * 2] = m[0] * xs[i] + m[1] * ys[i] + m[2];
            corners[i * 2 + 1] = m[3] * xs[i] + m[4] * ys[i] + m[5];
        }
        return corners;
    }

    /** Axis-aligned bounds of the transformed object. */
    private static float[] worldAabb(PageObject object) {
        return boundsOf(worldCorners(object));
    }

    private static float[] boundsOf(float[] flat) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < flat.length; i += 2) {
            l = Math.min(l, flat[i]);
            t = Math.min(t, flat[i + 1]);
            r = Math.max(r, flat[i]);
            b = Math.max(b, flat[i + 1]);
        }
        return new float[]{l, t, r, b};
    }

    private static boolean boundsIntersect(float[] a, float[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }
}
