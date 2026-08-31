package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pixel cutting, whole-stroke erase, area erase and layer filtering. */
public class EraserEngineTest {

    private final EraserEngine engine = new EraserEngine();

    private static Stroke makeStroke(float y, float fromX, float toX, Stroke.Layer layer) {
        Stroke s = new Stroke();
        s.brushPreset = BrushPreset.fountainPen();
        s.brushPreset.taperStart = 0f;
        s.brushPreset.taperEnd = 0f;
        s.color = 0xFF000000;
        s.layer = layer;
        int count = (int) ((toX - fromX) / 5f) + 1;
        for (int i = 0; i < count; i++) {
            float x = fromX + i * 5f;
            StrokePoint p = new StrokePoint(x, y, i * 8L, 0.5f);
            p.toolType = PressureSource.TOOL_TYPE_STYLUS;
            s.points.add(p);
        }
        return s;
    }

    @Test
    public void pixelErase_cutsStrokeIntoTwo() {
        Stroke s = makeStroke(100f, 0f, 200f, Stroke.Layer.INK);
        // Vertical eraser swipe through the middle (x=100, y 80..120).
        float[] eraserPath = {100f, 80f, 100f, 120f};
        EraserEngine.EraseResult result = engine.erase(
                Arrays.asList(s), eraserPath, 2f,
                EraserEngine.Mode.PIXEL, EraserEngine.TypeFilter.ALL);

        assertEquals(1, result.removed.size());
        assertSame(s, result.removed.get(0));
        assertEquals(2, result.remaining.size());
        Stroke left = result.remaining.get(0);
        Stroke right = result.remaining.get(1);
        assertTrue(left.points.size() >= 2);
        assertTrue(right.points.size() >= 2);
        // Left piece ends left of the eraser, right piece starts right of it.
        float halfWidth = s.brushPreset.baseWidth / 2f;
        float leftEnd = left.points.get(left.points.size() - 1).x;
        float rightStart = right.points.get(0).x;
        assertTrue(leftEnd <= 100f - 2f - halfWidth + 1f);
        assertTrue(rightStart >= 100f + 2f + halfWidth - 1f);
        // Cut pieces inherit the brush and layer.
        assertSame(s.brushPreset, left.brushPreset);
        assertEquals(Stroke.Layer.INK, left.layer);
    }

    @Test
    public void pixelErase_missesUntouchedStroke() {
        Stroke s = makeStroke(100f, 0f, 200f, Stroke.Layer.INK);
        float[] farAway = {500f, 500f, 600f, 500f};
        EraserEngine.EraseResult result = engine.erase(
                Arrays.asList(s), farAway, 10f,
                EraserEngine.Mode.PIXEL, EraserEngine.TypeFilter.ALL);
        assertEquals(0, result.removed.size());
        assertEquals(1, result.remaining.size());
        assertSame(s, result.remaining.get(0));
    }

    @Test
    public void strokeErase_removesWholeStroke() {
        Stroke s = makeStroke(100f, 0f, 200f, Stroke.Layer.INK);
        float[] touch = {100f, 99f}; // single tap on the stroke
        EraserEngine.EraseResult result = engine.erase(
                Arrays.asList(s), touch, 3f,
                EraserEngine.Mode.STROKE, EraserEngine.TypeFilter.ALL);
        assertEquals(1, result.removed.size());
        assertTrue(result.remaining.isEmpty());
    }

    @Test
    public void typeFilter_highlighterOnlyLeavesInkUntouched() {
        Stroke ink = makeStroke(100f, 0f, 200f, Stroke.Layer.INK);
        Stroke highlight = makeStroke(100f, 0f, 200f, Stroke.Layer.HIGHLIGHT);
        float[] eraserPath = {100f, 80f, 100f, 120f};
        EraserEngine.EraseResult result = engine.erase(
                Arrays.asList(ink, highlight), eraserPath, 3f,
                EraserEngine.Mode.STROKE, EraserEngine.TypeFilter.HIGHLIGHT_ONLY);
        assertEquals(1, result.removed.size());
        assertSame(highlight, result.removed.get(0));
        assertSame(ink, result.remaining.get(0));
    }

    @Test
    public void areaErase_cutsByPolygon() {
        Stroke s = makeStroke(100f, 0f, 200f, Stroke.Layer.INK);
        // Rectangle region x in [95,105], y in [90,110].
        float[] rect = {95f, 90f, 105f, 90f, 105f, 110f, 95f, 110f};
        EraserEngine.EraseResult result = engine.eraseArea(
                Arrays.asList(s), rect, EraserEngine.TypeFilter.ALL);
        assertEquals(1, result.removed.size());
        assertEquals(2, result.remaining.size());
    }

    @Test
    public void areaErase_removesFullyContainedStroke() {
        Stroke s = makeStroke(100f, 10f, 50f, Stroke.Layer.INK);
        float[] big = {0f, 0f, 500f, 0f, 500f, 500f, 0f, 500f};
        EraserEngine.EraseResult result = engine.eraseArea(
                Arrays.asList(s), big, EraserEngine.TypeFilter.ALL);
        assertEquals(1, result.removed.size());
        assertTrue(result.remaining.isEmpty());
    }

    @Test
    public void pixelErase_fullyErasedStrokeLeavesNothing() {
        Stroke s = makeStroke(100f, 90f, 110f, Stroke.Layer.INK);
        float[] eraserPath = {100f, 100f};
        EraserEngine.EraseResult result = engine.erase(
                new ArrayList<>(Arrays.asList(s)), eraserPath, 50f,
                EraserEngine.Mode.PIXEL, EraserEngine.TypeFilter.ALL);
        assertEquals(1, result.removed.size());
        assertTrue(result.remaining.isEmpty());
    }
}
