package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-JVM tests for the text box resize clamp used by the armed TEXT_BOX
 * selection gesture (spec 18.2): the top-left corner stays anchored and the
 * result never shrinks below the minimum width / one-line height.
 */
public class ObjectToolControllerTest {

    private static final float[] BASE = {100f, 200f, 360f, 260f};
    private static final float MIN_WIDTH = 80f;
    private static final float MIN_HEIGHT = 18f * 1.2f;

    @Test
    public void resizeBeyondMinimumKeepsRequestedCorner() {
        float[] out = ObjectToolController.clampResizedBounds(
                BASE, 500f, 400f, MIN_WIDTH, MIN_HEIGHT);
        assertEquals(100f, out[0], 0.001f);
        assertEquals(200f, out[1], 0.001f);
        assertEquals(500f, out[2], 0.001f);
        assertEquals(400f, out[3], 0.001f);
    }

    @Test
    public void widthClampsToMinimum() {
        float[] out = ObjectToolController.clampResizedBounds(
                BASE, 120f, 400f, MIN_WIDTH, MIN_HEIGHT);
        assertEquals(100f + MIN_WIDTH, out[2], 0.001f);
        assertEquals(400f, out[3], 0.001f);
    }

    @Test
    public void heightClampsToOneLine() {
        float[] out = ObjectToolController.clampResizedBounds(
                BASE, 500f, 205f, MIN_WIDTH, MIN_HEIGHT);
        assertEquals(500f, out[2], 0.001f);
        assertEquals(200f + MIN_HEIGHT, out[3], 0.001f);
    }

    @Test
    public void shrinkingPastTopLeftClampsBothAxes() {
        float[] out = ObjectToolController.clampResizedBounds(
                BASE, 50f, 150f, MIN_WIDTH, MIN_HEIGHT);
        assertEquals(100f, out[0], 0.001f);
        assertEquals(200f, out[1], 0.001f);
        assertEquals(100f + MIN_WIDTH, out[2], 0.001f);
        assertEquals(200f + MIN_HEIGHT, out[3], 0.001f);
    }

    @Test
    public void baseArrayIsNotMutated() {
        float[] base = BASE.clone();
        ObjectToolController.clampResizedBounds(base, 10f, 10f, MIN_WIDTH, MIN_HEIGHT);
        assertEquals(360f, base[2], 0.001f);
        assertEquals(260f, base[3], 0.001f);
    }
}
