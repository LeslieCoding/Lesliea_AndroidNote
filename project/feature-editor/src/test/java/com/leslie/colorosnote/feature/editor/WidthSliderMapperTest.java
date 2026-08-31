package com.leslie.colorosnote.feature.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Width slider mapping: 0.5..60pt in 0.5pt steps. */
public class WidthSliderMapperTest {

    @Test
    public void minProgressMapsToHalfPoint() {
        assertEquals(0.5f, WidthSliderMapper.progressToWidth(0), 1e-6f);
    }

    @Test
    public void maxProgressMapsToSixtyPoints() {
        assertEquals(60f, WidthSliderMapper.progressToWidth(WidthSliderMapper.MAX_PROGRESS), 1e-6f);
    }

    @Test
    public void negativeProgressClampsToMin() {
        assertEquals(0.5f, WidthSliderMapper.progressToWidth(-3), 1e-6f);
        assertEquals(0, WidthSliderMapper.widthToProgress(0.1f));
    }

    @Test
    public void overMaxProgressClampsToMax() {
        assertEquals(60f, WidthSliderMapper.progressToWidth(WidthSliderMapper.MAX_PROGRESS + 50), 1e-6f);
        assertEquals(WidthSliderMapper.MAX_PROGRESS, WidthSliderMapper.widthToProgress(999f));
    }

    @Test
    public void roundTripAcrossWholeRange() {
        for (int p = 0; p <= WidthSliderMapper.MAX_PROGRESS; p++) {
            float w = WidthSliderMapper.progressToWidth(p);
            assertEquals("progress " + p, p, WidthSliderMapper.widthToProgress(w));
        }
    }

    @Test
    public void oldIntegerWidthsLandOnExactSteps() {
        // Presets saved by the old 1..40 integer slider must restore exactly.
        for (int old = 1; old <= 40; old++) {
            float w = WidthSliderMapper.progressToWidth(WidthSliderMapper.widthToProgress(old));
            assertEquals("width " + old, old, w, 1e-6f);
        }
    }

    @Test
    public void labelFormatsWholeAndHalfPoints() {
        assertEquals("3 pt", WidthSliderMapper.label(3f));
        assertEquals("0.5 pt", WidthSliderMapper.label(0.5f));
        assertEquals("2.5 pt", WidthSliderMapper.label(2.49f)); // snaps to 2.5
    }

    @Test
    public void minIsWellBelowOldFloor() {
        // The old slider bottomed at 1pt; the new floor must be clearly lower.
        assertTrue(WidthSliderMapper.MIN_WIDTH <= 0.5f);
        assertTrue(WidthSliderMapper.MAX_WIDTH > 40f);
    }

    @Test
    public void fountainPenFloorIsHigherThanOtherPens() {
        assertTrue(WidthSliderMapper.FOUNTAIN_PEN_MIN_WIDTH > WidthSliderMapper.MIN_WIDTH);
        assertEquals(WidthSliderMapper.FOUNTAIN_PEN_MIN_WIDTH,
                WidthSliderMapper.minWidthFor(EditorTool.FOUNTAIN_PEN), 1e-6f);
        assertEquals(WidthSliderMapper.MIN_WIDTH,
                WidthSliderMapper.minWidthFor(EditorTool.BALLPOINT), 1e-6f);
        assertEquals(WidthSliderMapper.MIN_WIDTH,
                WidthSliderMapper.minWidthFor(EditorTool.PENCIL), 1e-6f);
        assertEquals(WidthSliderMapper.MIN_WIDTH,
                WidthSliderMapper.minWidthFor(EditorTool.HIGHLIGHTER), 1e-6f);
    }

    @Test
    public void fountainPenSliderStartsAtItsFloor() {
        float min = WidthSliderMapper.FOUNTAIN_PEN_MIN_WIDTH;
        assertEquals(min, WidthSliderMapper.progressToWidth(0, min), 1e-6f);
        assertEquals(min, WidthSliderMapper.progressToWidth(-3, min), 1e-6f);
        assertEquals(60f, WidthSliderMapper.progressToWidth(
                WidthSliderMapper.maxProgress(min), min), 1e-6f);
        assertEquals(0, WidthSliderMapper.widthToProgress(min - 1f, min));
        // A stored 0.5pt fountain preset restores to the raised floor.
        assertEquals(0, WidthSliderMapper.widthToProgress(0.5f, min));
    }
}
