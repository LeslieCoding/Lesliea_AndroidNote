package com.leslie.colorosnote.core.render;

import com.leslie.colorosnote.core.model.Stroke;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StrokeSnapshotDeltaTest {

    private static Stroke stroke(String id) {
        Stroke stroke = new Stroke();
        stroke.id = id;
        return stroke;
    }

    @Test
    public void commitDuringRenderIsPatchableAppend() {
        Stroke a = stroke("a");
        Stroke b = stroke("b");
        StrokeSnapshotDelta delta = StrokeSnapshotDelta.between(
                Collections.singletonList(a), Arrays.asList(a, b));
        assertTrue(delta.appendOnly);
        assertEquals(Collections.singletonList(b), delta.additions);
    }

    @Test
    public void eraseDuringRenderRequiresCleanRerender() {
        Stroke a = stroke("a");
        Stroke b = stroke("b");
        StrokeSnapshotDelta delta = StrokeSnapshotDelta.between(
                Arrays.asList(a, b), Collections.singletonList(a));
        assertFalse(delta.appendOnly);
        assertTrue(delta.additions.isEmpty());
    }

    @Test
    public void replacementIsNotMistakenForEqualCount() {
        StrokeSnapshotDelta delta = StrokeSnapshotDelta.between(
                Collections.singletonList(stroke("old")),
                Collections.singletonList(stroke("new")));
        assertFalse(delta.appendOnly);
        assertEquals(1, delta.additions.size());
    }
}
