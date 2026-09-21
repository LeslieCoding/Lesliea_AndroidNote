package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * v4.0.0 regression lock: a tap dot (coincident points, zero-size bounds)
 * must be measured by its brush-width ink, not its point spread, so the LOD
 * cull never erases it from re-renders ("打点后缩放，黑点消失").
 */
public class PageRendererLodTest {

    /** Zero-size bounds of a tap dot at (50, 50). */
    private static final float[] DOT_BOUNDS = {50f, 50f, 50f, 50f};
    /** Fountain-pen width pad (max(base, max) ≈ 3 units). */
    private static final float DOT_PAD = 3f;

    @Test
    public void dotSurvivesLodCullAtFitZoom() {
        // Fit zoom 1.25: a 3-unit dot projects to ~5px — clearly visible.
        assertFalse(PageRenderer.isBelowVisibleDetail(1.25f, DOT_BOUNDS, DOT_PAD));
    }

    @Test
    public void dotSurvivesLodCullAtDeepZoom() {
        assertFalse(PageRenderer.isBelowVisibleDetail(12.5f, DOT_BOUNDS, DOT_PAD));
    }

    @Test
    public void dotStillCulledWhenTrulyInvisible() {
        // Zoomed far out the same dot is a sub-pixel speck: cull as before.
        assertTrue(PageRenderer.isBelowVisibleDetail(0.1f, DOT_BOUNDS, DOT_PAD));
    }

    @Test
    public void pointSpreadStillMeasuredForRealStrokes() {
        // A stroke with zero width pad but a real point spread: unchanged.
        float[] line = {0f, 0f, 10f, 10f};
        assertFalse(PageRenderer.isBelowVisibleDetail(1.25f, line, 0f));
        assertTrue(PageRenderer.isBelowVisibleDetail(0.01f, line, 0f));
    }
}
