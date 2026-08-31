package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-Java contracts of {@link TapePainter}: the two-tone color math and the
 * width-proportional pattern metrics that keep the washi texture fine at any
 * tape width.
 */
public class TapePainterTest {

    @Test
    public void shadeDarkensRgbAndKeepsAlpha() {
        int shaded = TapePainter.shade(0x80E0B4B0);
        assertEquals(0x80, (shaded >> 24) & 0xFF);
        assertEquals((int) (0xE0 * 0.82f), (shaded >> 16) & 0xFF);
        assertEquals((int) (0xB4 * 0.82f), (shaded >> 8) & 0xFF);
        assertEquals((int) (0xB0 * 0.82f), shaded & 0xFF);
    }

    @Test
    public void lightenMovesRgbTowardsWhiteAndKeepsAlpha() {
        int light = TapePainter.lighten(0xFFA8BCC9);
        assertEquals(0xFF, (light >> 24) & 0xFF);
        assertEquals(0xA8 + (int) ((255 - 0xA8) * 0.35f), (light >> 16) & 0xFF);
        assertTrue((light & 0xFF) >= 0xC9);
    }

    @Test
    public void inkIsShadedColorAtSoftPatternAlpha() {
        int ink = TapePainter.ink(0xFFE0B4B0);
        assertEquals(150, (ink >> 24) & 0xFF);
        assertEquals(TapePainter.shade(0xFFE0B4B0) & 0x00FFFFFF, ink & 0x00FFFFFF);
    }

    @Test
    public void patternMetricsScaleWithStripWidth() {
        // Reference width 40: fine, evenly spaced washi texture.
        assertEquals(12f, TapePainter.stripeSpacing(40f), 0.001f);
        assertEquals(12f, TapePainter.dotSpacing(40f), 0.001f);
        assertEquals(2.2f, TapePainter.dotRadius(40f), 0.001f);
        assertEquals(12f, TapePainter.gridSpacing(40f), 0.001f);
        assertEquals(13.6f, TapePainter.hatchSpacing(40f), 0.001f);
        assertEquals(13.6f, TapePainter.waveSpacing(40f), 0.001f);
        assertEquals(17.6f, TapePainter.heartSpacing(40f), 0.001f);
        assertEquals(1.4f, TapePainter.fineLine(40f), 0.001f);
        assertEquals(1.12f, TapePainter.hairLine(40f), 0.001f);
        // Thin tape shrinks proportionally, thick tape grows.
        assertTrue(TapePainter.dotSpacing(24f) < TapePainter.dotSpacing(40f));
        assertTrue(TapePainter.dotSpacing(64f) > TapePainter.dotSpacing(40f));
    }

    @Test
    public void patternMetricsNeverCollapseOnTinyWidths() {
        // Very narrow strips still get drawable, non-degenerate geometry.
        assertTrue(TapePainter.stripeSpacing(1f) >= 6f);
        assertTrue(TapePainter.dotRadius(1f) >= 1.1f);
        assertTrue(TapePainter.fineLine(1f) >= 1.1f);
        assertTrue(TapePainter.hairLine(1f) >= 1f);
        assertTrue(TapePainter.heartSpacing(1f) >= 8f);
    }
}
