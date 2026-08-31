package com.leslie.colorosnote.core.render;

import com.leslie.colorosnote.core.model.Stroke;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares an off-thread render snapshot with the live stroke list.
 *
 * <p>New strokes can finish while a base/window bitmap is rendering. Those
 * additions are safe to paint into the fresh bitmap immediately before swap;
 * removals or replacements require a clean re-render because pixels cannot be
 * subtracted from the already-rendered bitmap.
 */
final class StrokeSnapshotDelta {

    final List<Stroke> additions;
    final boolean appendOnly;

    private StrokeSnapshotDelta(List<Stroke> additions, boolean appendOnly) {
        this.additions = additions;
        this.appendOnly = appendOnly;
    }

    static StrokeSnapshotDelta between(List<Stroke> snapshot,
                                       List<Stroke> current) {
        Set<String> snapshotIds = ids(snapshot);
        Set<String> currentIds = ids(current);
        List<Stroke> additions = new ArrayList<>();
        for (Stroke stroke : current) {
            if (!snapshotIds.contains(stroke.id)) {
                additions.add(stroke);
            }
        }
        return new StrokeSnapshotDelta(additions,
                currentIds.containsAll(snapshotIds));
    }

    private static Set<String> ids(List<Stroke> strokes) {
        Set<String> out = new HashSet<>();
        for (Stroke stroke : strokes) {
            out.add(stroke.id);
        }
        return out;
    }
}
