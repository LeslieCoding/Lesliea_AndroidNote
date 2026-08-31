package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.TapeObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-JVM point hit-testing for page objects (spec 16/19): finds the topmost
 * object under a page-space point by mapping the point into each object's
 * local space via the inverse of its 9-float transform. Tape objects use
 * strip-aware tests (path distance / rect), everything else uses padded local
 * bounds. No android.graphics so it runs on the JVM.
 */
public final class ObjectHitTester {

    private ObjectHitTester() {
    }

    /** Topmost unlocked object at (x, y), or null. */
    public static PageObject topmostAt(List<PageObject> objects, float x, float y, float slop) {
        return topmostAt(objects, x, y, slop, false);
    }

    /**
     * Topmost object at (x, y) in zIndex order, or null.
     *
     * @param includeLocked when false, locked objects are skipped (they are
     *                      not editable); pass true to offer "unlock" menus.
     */
    public static PageObject topmostAt(List<PageObject> objects, float x, float y,
                                       float slop, boolean includeLocked) {
        List<PageObject> sorted = new ArrayList<>(objects);
        Collections.sort(sorted, Comparator.comparingInt(o -> -o.zIndex));
        for (PageObject object : sorted) {
            if (object.locked && !includeLocked) {
                continue;
            }
            float[] inverse = invertAffine(object.transform);
            if (inverse == null) {
                continue;
            }
            float lx = inverse[0] * x + inverse[1] * y + inverse[2];
            float ly = inverse[3] * x + inverse[4] * y + inverse[5];
            if (hitLocal(object, lx, ly, slop)) {
                return object;
            }
        }
        return null;
    }

    private static boolean hitLocal(PageObject object, float lx, float ly, float slop) {
        if (object instanceof TapeObject) {
            TapeObject tape = (TapeObject) object;
            if (tape.rect != null && tape.rect.length >= 4) {
                return lx >= tape.rect[0] - slop && lx <= tape.rect[2] + slop
                        && ly >= tape.rect[1] - slop && ly <= tape.rect[3] + slop;
            }
            if (tape.path != null && tape.path.size() >= 2) {
                return TapeGeometry.distanceToPath(tape.path, lx, ly)
                        <= tape.width / 2f + slop;
            }
            return false;
        }
        float[] b = object.bounds;
        return lx >= b[0] - slop && lx <= b[2] + slop
                && ly >= b[1] - slop && ly <= b[3] + slop;
    }

    /** Inverse of a 3x3 affine in Matrix-values order; null when degenerate. */
    static float[] invertAffine(float[] m) {
        float a = m[0], b = m[1], c = m[2];
        float d = m[3], e = m[4], f = m[5];
        float det = a * e - b * d;
        if (Math.abs(det) < 1e-8f) {
            return null;
        }
        float invDet = 1f / det;
        float ia = e * invDet;
        float ib = -b * invDet;
        float id = -d * invDet;
        float ie = a * invDet;
        float ic = -(ia * c + ib * f);
        float if_ = -(id * c + ie * f);
        return new float[]{ia, ib, ic, id, ie, if_, 0f, 0f, 1f};
    }
}
