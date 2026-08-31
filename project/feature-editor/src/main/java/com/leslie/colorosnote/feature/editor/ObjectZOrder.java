package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.model.PageObject;

import java.util.List;

/**
 * zIndex computation for 置顶/置底 (spec 18.1). Objects render in ascending
 * zIndex order (see PageRenderer#drawObjects), so "front" is max + 1 and
 * "back" is min - 1. Pure JVM.
 */
public final class ObjectZOrder {

    private ObjectZOrder() {
    }

    /** zIndex that places an object above everything else on the page. */
    public static int frontZIndex(List<PageObject> objects) {
        int max = 0;
        for (PageObject o : objects) {
            max = Math.max(max, o.zIndex);
        }
        return max + 1;
    }

    /** zIndex that places an object below everything else on the page. */
    public static int backZIndex(List<PageObject> objects) {
        int min = 0;
        for (PageObject o : objects) {
            min = Math.min(min, o.zIndex);
        }
        return min - 1;
    }
}
