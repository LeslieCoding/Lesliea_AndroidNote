package com.leslie.colorosnote.core.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for extreme-zoom live-ink geometry budgets. */
public class LiveInkQualityPolicyTest {

    /** A4 portrait fitted into the OPPO Pad's ink view. */
    private static final float PAD_FIT_SCALE = 1.25f;

    @Test
    public void normalZoomKeepsNativeScale() {
        assertEquals(3f, LiveInkQualityPolicy.tessellationScale(
                3f, 1f, 10f, 10f), 1e-5f);
    }

    @Test
    public void extremeZoomIsCappedEvenAtRest() {
        assertEquals(LiveInkQualityPolicy.MAX_IDLE_ZOOM,
                LiveInkQualityPolicy.tessellationScale(64f, 1f, 0f, 0f), 1e-5f);
    }

    @Test
    public void fastWritingUsesSmallerTransientBudget() {
        float slow = LiveInkQualityPolicy.tessellationScale(64f, 1f, 2f, 0f);
        float fast = LiveInkQualityPolicy.tessellationScale(64f, 1f, 40f, 0f);
        assertTrue(fast < slow);
        assertEquals(LiveInkQualityPolicy.MIN_FAST_ZOOM, fast, 1e-5f);
    }

    @Test
    public void invalidInputsNeverPoisonRendering() {
        assertEquals(1f, LiveInkQualityPolicy.tessellationScale(
                Float.NaN, Float.NaN, Float.NaN, Float.POSITIVE_INFINITY), 0f);
    }

    // ------------------------------------------------------------------
    // v2.5.0: the budgets are zoom levels, not absolute scales
    // ------------------------------------------------------------------

    /**
     * At the 1000% zoom cap and at rest, live tessellation must track the
     * viewport exactly. Before v2.5.0 the cap was the bare constant 10, so on
     * this device (fit scale 1.25, making 1000% an absolute scale of 12.5)
     * idle tessellation silently ran at 80% of the viewport.
     */
    @Test
    public void atRestAtMaxZoomTessellationMatchesTheViewport() {
        float maxZoomScale = PAD_FIT_SCALE * 10f;
        assertEquals(maxZoomScale, LiveInkQualityPolicy.tessellationScale(
                maxZoomScale, PAD_FIT_SCALE, 0f, 0f), 1e-4f);
    }

    /**
     * Fast writing backs off to 80% of the viewport — the figure the v2.1.3
     * comment claimed but did not actually deliver here (it was 64%).
     */
    @Test
    public void fastWritingAtMaxZoomBacksOffToEightyPercentOfTheViewport() {
        float maxZoomScale = PAD_FIT_SCALE * 10f;
        float fast = LiveInkQualityPolicy.tessellationScale(
                maxZoomScale, PAD_FIT_SCALE, 4000f, 0f);
        assertEquals(0.8f, fast / maxZoomScale, 1e-3f);
    }

    /**
     * The budget must mean the same thing on a denser screen or a smaller
     * page, where the fit scale — and so the absolute scale at any given zoom
     * level — is different.
     */
    @Test
    public void budgetIsIndependentOfHowLargeTheFitScaleIs() {
        for (float fit : new float[] {0.6f, 1.25f, 2.5f, 4f}) {
            float maxZoomScale = fit * 10f;
            float idle = LiveInkQualityPolicy.tessellationScale(
                    maxZoomScale, fit, 0f, 0f);
            assertEquals("fit=" + fit, 1f, idle / maxZoomScale, 1e-3f);
        }
    }
}
