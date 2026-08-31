package com.leslie.colorosnote.core.ink;

import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Rect/free lasso hit-testing and type filtering. */
public class LassoEngineTest {

    private final LassoEngine engine = new LassoEngine();

    private static Stroke strokeAt(float x, float y, Stroke.Layer layer) {
        Stroke s = new Stroke();
        s.layer = layer;
        s.points.add(new StrokePoint(x, y, 0L, 0.5f));
        s.points.add(new StrokePoint(x + 20f, y, 8L, 0.5f));
        return s;
    }

    private static ImageObject imageAt(float left, float top, float right, float bottom) {
        ImageObject o = new ImageObject();
        o.bounds = new float[]{left, top, right, bottom};
        o.transform = PageObject.identityMatrix();
        return o;
    }

    private static Page samplePage() {
        Page page = new Page();
        page.strokes.add(strokeAt(100f, 100f, Stroke.Layer.INK));        // inside
        page.strokes.add(strokeAt(400f, 400f, Stroke.Layer.INK));        // outside
        page.strokes.add(strokeAt(120f, 120f, Stroke.Layer.HIGHLIGHT));  // inside, highlight
        page.objects.add(imageAt(60f, 60f, 90f, 90f));                   // inside
        page.objects.add(imageAt(500f, 500f, 560f, 560f));               // outside
        return page;
    }

    @Test
    public void rectSelect_hitsInsideStrokesAndObjects() {
        Page page = samplePage();
        LassoEngine.Selection sel = engine.select(page,
                new float[]{50f, 50f, 200f, 200f},
                LassoEngine.Mode.RECT, LassoEngine.ALL_TYPES);
        assertEquals(2, sel.strokes.size());
        assertEquals(1, sel.objects.size());
        assertTrue(sel.strokes.contains(page.strokes.get(0)));
        assertTrue(sel.strokes.contains(page.strokes.get(2)));
        assertTrue(sel.objects.contains(page.objects.get(0)));
        assertFalse(sel.isEmpty());
    }

    @Test
    public void freeLasso_polygonHit() {
        Page page = samplePage();
        // Triangle covering only (100..130, 100..130)-ish area.
        float[] triangle = {60f, 60f, 160f, 60f, 60f, 160f};
        LassoEngine.Selection sel = engine.select(page, triangle,
                LassoEngine.Mode.FREE, LassoEngine.ALL_TYPES);
        assertTrue(sel.strokes.contains(page.strokes.get(0)));
        assertFalse(sel.strokes.contains(page.strokes.get(1)));
        assertEquals(1, sel.objects.size());
    }

    @Test
    public void typeFilter_inkOnlyExcludesHighlightAndObjects() {
        Page page = samplePage();
        LassoEngine.Selection sel = engine.select(page,
                new float[]{0f, 0f, 600f, 600f},
                LassoEngine.Mode.RECT,
                EnumSet.of(LassoEngine.TypeFilter.INK));
        assertEquals(2, sel.strokes.size());
        assertTrue(sel.strokes.contains(page.strokes.get(0)));
        assertTrue(sel.strokes.contains(page.strokes.get(1)));
        assertTrue(sel.objects.isEmpty());
    }

    @Test
    public void typeFilter_imageOnly() {
        Page page = samplePage();
        LassoEngine.Selection sel = engine.select(page,
                new float[]{0f, 0f, 600f, 600f},
                LassoEngine.Mode.RECT,
                EnumSet.of(LassoEngine.TypeFilter.IMAGE));
        assertTrue(sel.strokes.isEmpty());
        assertEquals(2, sel.objects.size());
    }

    @Test
    public void segmentCrossing_regionWithoutPointInsideHits() {
        // Long horizontal stroke whose samples all sit outside the region,
        // but whose segment crosses it.
        Stroke s = new Stroke();
        s.points.add(new StrokePoint(0f, 100f, 0L, 0.5f));
        s.points.add(new StrokePoint(300f, 100f, 8L, 0.5f));
        LassoEngine.Selection sel = engine.select(Arrays.asList(s),
                java.util.Collections.<PageObject>emptyList(),
                new float[]{140f, 60f, 160f, 140f},
                LassoEngine.Mode.RECT, LassoEngine.ALL_TYPES);
        assertEquals(1, sel.strokes.size());
    }

    @Test
    public void select_skipsLockedObjects() {
        Page page = new Page();
        ImageObject locked = imageAt(60f, 60f, 90f, 90f);
        locked.locked = true;
        ImageObject free = imageAt(100f, 100f, 130f, 130f);
        page.objects.add(locked);
        page.objects.add(free);
        LassoEngine.Selection sel = engine.select(page,
                new float[]{50f, 50f, 200f, 200f},
                LassoEngine.Mode.RECT, LassoEngine.ALL_TYPES);
        assertEquals(1, sel.objects.size());
        assertTrue(sel.objects.contains(free));
    }

    @Test
    public void selectAt_hitsTopmostUnlockedObject() {
        Page page = new Page();
        ImageObject bottom = imageAt(0f, 0f, 100f, 100f);
        bottom.zIndex = 1;
        ImageObject lockedTop = imageAt(0f, 0f, 100f, 100f);
        lockedTop.zIndex = 3;
        lockedTop.locked = true;
        ImageObject middle = imageAt(0f, 0f, 100f, 100f);
        middle.zIndex = 2;
        page.objects.add(bottom);
        page.objects.add(lockedTop);
        page.objects.add(middle);
        LassoEngine.Selection sel = engine.selectAt(page, 50f, 50f, 0f);
        assertEquals(1, sel.objects.size());
        assertTrue(sel.objects.contains(middle)); // zIndex 3 is locked
    }

    @Test
    public void selectAt_missReturnsEmpty() {
        Page page = new Page();
        page.objects.add(imageAt(0f, 0f, 100f, 100f));
        assertTrue(engine.selectAt(page, 150f, 50f, 0f).isEmpty());
        // Slop widens the hit area.
        assertEquals(1, engine.selectAt(page, 110f, 50f, 20f).objects.size());
    }
}
