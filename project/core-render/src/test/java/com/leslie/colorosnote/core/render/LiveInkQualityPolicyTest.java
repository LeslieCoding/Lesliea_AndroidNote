package com.leslie.colorosnote.core.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for extreme-zoom live-ink geometry budgets. */
public class LiveInkQualityPolicyTest {

    @Test
    public void normalZoomKeepsNativeScale() {
        assertEquals(3f, LiveInkQualityPolicy.tessellationScale(
                3f, 10f, 10f), 1e-5f);
    }

    @Test
    public void extremeZoomIsCappedEvenAtRest() {
        assertEquals(LiveInkQualityPolicy.MAX_IDLE_SCALE,
                LiveInkQualityPolicy.tessellationScale(64f, 0f, 0f), 1e-5f);
    }

    @Test
    public void fastWritingUsesSmallerTransientBudget() {
        float slow = LiveInkQualityPolicy.tessellationScale(64f, 2f, 0f);
        float fast = LiveInkQualityPolicy.tessellationScale(64f, 40f, 0f);
        assertTrue(fast < slow);
        assertEquals(LiveInkQualityPolicy.MIN_FAST_SCALE, fast, 1e-5f);
    }

    @Test
    public void invalidInputsNeverPoisonRendering() {
        assertEquals(1f, LiveInkQualityPolicy.tessellationScale(
                Float.NaN, Float.NaN, Float.POSITIVE_INFINITY), 0f);
    }
}
