package com.leslie.colorosnote.core.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Zoom-adaptive bake-scale policy: triggers, headroom, caps. */
public class RenderScalePolicyTest {

    private static final float A4_W = 595.28f;
    private static final float A4_H = 841.89f;

    @Test
    public void fitZoomKeepsBaseScale() {
        // Typical fit-to-screen scale on the OPPO Pad (~2.4-2.6): no re-bake,
        // initial render stays at 2x (memory/perf unchanged at rest).
        assertEquals(RenderScalePolicy.BASE_SCALE,
                RenderScalePolicy.initialScale(2.44f, A4_W, A4_H), 1e-4f);
        assertFalse(RenderScalePolicy.needsRebake(2.44f,
                RenderScalePolicy.BASE_SCALE, A4_W, A4_H));
        assertFalse(RenderScalePolicy.needsRebake(2.5f,
                RenderScalePolicy.BASE_SCALE, A4_W, A4_H)); // exactly at trigger
    }

    @Test
    public void zoomInTriggersRebakeWithHeadroom() {
        assertTrue(RenderScalePolicy.needsRebake(3.0f,
                RenderScalePolicy.BASE_SCALE, A4_W, A4_H));
        assertEquals(3.0f * 1.15f,
                RenderScalePolicy.chooseBakeScale(3.0f, A4_W, A4_H), 1e-4f);
    }

    @Test
    public void memoryCapBindsForA4() {
        // A4 costs ~2.0MB·scale²; the 40MB cap lands at ~4.58x.
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        assertEquals(4.58f, cap, 0.01f);
        float bytes = 4f * A4_W * A4_H * cap * cap;
        assertTrue("must fit the per-page byte cap (float roundtrip epsilon)",
                bytes <= RenderScalePolicy.MAX_PAGE_BYTES * 1.0001f);
    }

    @Test
    public void textureGuardBindsForHugePages() {
        // 1100x100-unit page: memory would allow ~9.8x, texture guard wins.
        float cap = RenderScalePolicy.chooseBakeScale(100f, 1100f, 100f);
        assertEquals(RenderScalePolicy.TEXTURE_GUARD, cap, 1e-4f);
    }

    @Test
    public void noRebakeLoopAtCap() {
        // Already baked at the cap and zoomed far past it: no re-bake, or the
        // page would re-render forever without ever getting sharper.
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        assertFalse(RenderScalePolicy.needsRebake(8f, cap, A4_W, A4_H));
        assertFalse(RenderScalePolicy.needsRebake(100f, cap, A4_W, A4_H));
    }

    @Test
    public void zoomOutNeverRebakesDown() {
        // Baked at the cap, back at fit zoom: keep the sharp bitmap.
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        assertFalse(RenderScalePolicy.needsRebake(2.44f, cap, A4_W, A4_H));
    }

    @Test
    public void furtherZoomRebakesFromHighBase() {
        // Baked at 3.45x (viewport 3.0): zooming to 4.4 (>3.45*1.25) re-bakes.
        assertTrue(RenderScalePolicy.needsRebake(4.4f, 3.45f, A4_W, A4_H));
        assertFalse(RenderScalePolicy.needsRebake(4.2f, 3.45f, A4_W, A4_H));
    }

    @Test
    public void initialScaleAdaptsWhenOpenedZoomedIn() {
        float s = RenderScalePolicy.initialScale(3.0f, A4_W, A4_H);
        assertEquals(3.0f * 1.15f, s, 1e-4f);
    }

    // ------------------------------------------------------------------
    // Viewport window (v1.4.13)
    // ------------------------------------------------------------------

    @Test
    public void windowEngagesOnlyPastCap() {
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        // Baked at the cap, zoomed far past it: window mode on.
        assertTrue(RenderScalePolicy.shouldUseWindow(8f, cap, A4_W, A4_H, false));
        // Baked below the cap, same zoom: a full-page re-bake still helps, so
        // the cheaper re-bake path runs instead of window mode.
        assertFalse(RenderScalePolicy.shouldUseWindow(8f,
                RenderScalePolicy.BASE_SCALE, A4_W, A4_H, false));
        // At or below the baked scale: no window (nothing is stretched).
        assertFalse(RenderScalePolicy.shouldUseWindow(cap,
                cap, A4_W, A4_H, false));
        assertFalse(RenderScalePolicy.shouldUseWindow(cap * 0.9f,
                cap, A4_W, A4_H, false));
    }

    @Test
    public void windowEngagesImmediatelyPastBakedScale() {
        // v1.4.15: the old ×1.25 entry left a blur band — from the ~4.58x
        // bake cap up to ~5.73x the capped bitmap was stretched up to 25%
        // with no sharper layer. Entry is now exactly 1x the baked scale,
        // so every zoom past the cap is covered by the window.
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        assertTrue(RenderScalePolicy.shouldUseWindow(cap * 1.01f,
                cap, A4_W, A4_H, false));
        assertTrue(RenderScalePolicy.shouldUseWindow(cap * 1.2f,
                cap, A4_W, A4_H, false));
        assertTrue(RenderScalePolicy.shouldUseWindow(cap * 1.24f,
                cap, A4_W, A4_H, false));
    }

    @Test
    public void windowHysteresisPreventsFlapping() {
        // Entry 1.0x, exit 0.98x: in the (0.98, 1.0] band the current state
        // is kept. Exit MUST stay below entry — the old pairing (enter 1.25,
        // exit 1.1) would flap if entry simply dropped to 1.0.
        float cap = RenderScalePolicy.chooseBakeScale(100f, A4_W, A4_H);
        float between = cap * 0.99f; // > exit 0.98x, < entry 1.0x
        assertTrue(RenderScalePolicy.shouldUseWindow(between, cap, A4_W, A4_H, true));
        assertFalse(RenderScalePolicy.shouldUseWindow(between, cap, A4_W, A4_H, false));
        // Active and zoomed back under the exit threshold: window off.
        assertFalse(RenderScalePolicy.shouldUseWindow(cap * 0.95f,
                cap, A4_W, A4_H, true));
        // Active and back above entry: stays on.
        assertTrue(RenderScalePolicy.shouldUseWindow(cap * 1.05f,
                cap, A4_W, A4_H, true));
    }

    @Test
    public void computeWindowExpandsAndRendersAtViewportResolution() {
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                8f, A4_W, A4_H, 100f, 200f, 300f, 500f);
        // 15% margin per side: 200x300 -> 260x390 page units.
        assertEquals(70f, w.left, 1e-3f);
        assertEquals(155f, w.top, 1e-3f);
        assertEquals(330f, w.right, 1e-3f);
        assertEquals(545f, w.bottom, 1e-3f);
        assertEquals(2080, w.pixelW);
        assertEquals(3120, w.pixelH);
        assertEquals(8f, w.scale, 1e-4f);
        assertTrue((long) w.pixelW * w.pixelH * 4L
                <= RenderScalePolicy.MAX_WINDOW_BYTES);
    }

    @Test
    public void computeWindowClampsToPageEdges() {
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                8f, A4_W, A4_H, -60f, -40f, 140f, 260f);
        assertEquals(0f, w.left, 1e-3f);
        assertEquals(0f, w.top, 1e-3f);
        assertTrue(w.right <= A4_W && w.bottom <= A4_H);
    }

    @Test
    public void computeWindowPixelsIndependentOfZoom() {
        // Same 1600x2560 view at 8x (200x320 units) and 16x (100x160 units):
        // identical bitmap size — sharpness is device resolution at any zoom.
        RenderScalePolicy.WindowSpec w8 = RenderScalePolicy.computeWindow(
                8f, A4_W, A4_H, 100f, 200f, 300f, 520f);
        RenderScalePolicy.WindowSpec w16 = RenderScalePolicy.computeWindow(
                16f, A4_W, A4_H, 150f, 260f, 250f, 420f);
        assertEquals(w8.pixelW, w16.pixelW);
        assertEquals(w8.pixelH, w16.pixelH);
    }

    @Test
    public void computeWindowHonorsByteCap() {
        // Absurdly large view (4000x6400 px at 8x): margin dropped, then the
        // pixel scale shrunk — memory stays bounded either way.
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                8f, 2000f, 2000f, 0f, 0f, 500f, 800f);
        assertTrue("window bytes must stay bounded",
                (long) w.pixelW * w.pixelH * 4L
                        <= RenderScalePolicy.MAX_WINDOW_BYTES);
        assertTrue(w.scale <= 8f);
    }

    @Test
    public void windowCoversSmallPanButNotLarge() {
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                8f, A4_W, A4_H, 100f, 200f, 300f, 500f);
        // Pan 10 units right: still inside (slack fires before the edge).
        assertTrue(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                110f, 200f, 310f, 500f, 8f));
        // Pan 30 units right: visible edge crosses window edge minus slack.
        assertFalse(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                130f, 200f, 330f, 500f, 8f));
        // Zoom drift within 5% is fine; past it re-renders.
        assertTrue(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                100f, 200f, 300f, 500f, 8.1f));
        assertFalse(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                100f, 200f, 300f, 500f, 8.5f));
    }

    @Test
    public void windowCoversAtPageEdge() {
        // Window clamped to the left/top page border: panning further into
        // the gray workspace must not re-render (no ink out there).
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                8f, A4_W, A4_H, -60f, -40f, 140f, 260f);
        assertTrue(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                -80f, -60f, 120f, 240f, 8f));
    }

    @Test
    public void freshWindowCoversItsOwnView() {
        // v1.4.14 regression guard: InkView coalesces a follow-up render
        // whenever the in-flight spec fails windowCovers against the live
        // view. A spec just computed by computeWindow for that exact view
        // MUST pass, or every settled render re-triggers another one and
        // the window render loop never stops (20x black-screen storm).
        // Runtime values captured from the OPPO Pad repro (1600x2560 view,
        // ~46.2x viewport scale).
        float scale = 46.223278f;
        float visL = 280.19272f, visT = 399.43076f;
        float visR = 314.8073f, visB = 442.56918f;
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                scale, A4_W, A4_H, visL, visT, visR, visB);
        assertTrue("a fresh window must cover the view it was computed from",
                RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                        visL, visT, visR, visB, scale));
    }

    @Test
    public void freshWindowCoversItsOwnViewAtPageCorner() {
        // Same invariant when the view spills past the top-left page corner
        // (window rect clamped): still no spurious re-render after settling.
        float scale = 46.2f;
        RenderScalePolicy.WindowSpec w = RenderScalePolicy.computeWindow(
                scale, A4_W, A4_H, -10f, -12f, 24.6f, 31.2f);
        assertTrue(RenderScalePolicy.windowCovers(w, A4_W, A4_H,
                -10f, -12f, 24.6f, 31.2f, scale));
    }
}
