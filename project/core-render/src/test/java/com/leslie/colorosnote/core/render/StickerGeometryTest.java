package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Pure-JVM tests for the built-in sticker geometry (spec 18.3). */
public class StickerGeometryTest {

    @Test
    public void everyCatalogEntryHasGeometry() {
        for (StickerCatalog.Entry entry : StickerCatalog.BUILT_IN) {
            List<StickerGeometry.Prim> prims = StickerGeometry.primsFor(entry.id);
            assertNotNull("missing geometry for " + entry.id, prims);
            assertTrue("empty geometry for " + entry.id, !prims.isEmpty());
        }
    }

    @Test
    public void catalogCategoriesCoverAllEntries() {
        List<String> categories = StickerCatalog.categories();
        assertEquals(4, categories.size());
        int total = 0;
        for (String category : categories) {
            total += StickerCatalog.byCategory(category).size();
        }
        assertEquals(StickerCatalog.BUILT_IN.size(), total);
    }

    @Test
    public void catalogHasAtLeastTwentyStickers() {
        assertTrue("expected 20+ built-in stickers, got " + StickerCatalog.BUILT_IN.size(),
                StickerCatalog.BUILT_IN.size() >= 20);
        // 装饰 category carries the new decor designs.
        assertTrue(StickerCatalog.byCategory(StickerCatalog.CAT_DECOR).size() >= 5);
    }

    @Test
    public void everyStickerIdIsUnique() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (StickerCatalog.Entry entry : StickerCatalog.BUILT_IN) {
            assertTrue("duplicate sticker id " + entry.id, ids.add(entry.id));
        }
    }

    @Test
    public void unknownStickerHasNoGeometry() {
        assertNull(StickerGeometry.primsFor("does_not_exist"));
        assertNull(StickerGeometry.primsFor(null));
        assertNull(StickerCatalog.find("does_not_exist"));
    }

    @Test
    public void starPolygonAlternatesRadii() {
        float[] star = StickerGeometry.star(50f, 50f, 40f, 16f, 5);
        assertEquals(20, star.length); // 5 tips + 5 valleys
        for (int i = 0; i < 10; i++) {
            float dx = star[i * 2] - 50f;
            float dy = star[i * 2 + 1] - 50f;
            float r = (float) Math.hypot(dx, dy);
            float expected = (i % 2 == 0) ? 40f : 16f;
            assertEquals(expected, r, 0.01f);
        }
    }

    @Test
    public void annularSectorStaysBetweenRadii() {
        float[] sector = StickerGeometry.annularSector(50f, 50f, 30f, 20f, 0.0, 180.0);
        for (int i = 0; i + 1 < sector.length; i += 2) {
            float r = (float) Math.hypot(sector[i] - 50f, sector[i + 1] - 50f);
            assertTrue("radius " + r, r >= 19.9f && r <= 30.1f);
            // 0..180 degrees with y down: all points in the lower half.
            assertTrue(sector[i + 1] >= 49.9f);
        }
    }

    @Test
    public void rotatedRectCornersKeepDistanceFromCenter() {
        float[] rect = StickerGeometry.rotatedRect(50f, 50f, 80f, 20f, 33.0);
        double expected = Math.hypot(40.0, 10.0);
        for (int i = 0; i < 4; i++) {
            double r = Math.hypot(rect[i * 2] - 50f, rect[i * 2 + 1] - 50f);
            assertEquals(expected, r, 0.01);
        }
    }

    @Test
    public void crescentAlternatesOuterAndShiftedInnerArc() {
        float[] c = StickerGeometry.crescent(48f, 50f, 42f, 66f, 50f, 34f, 70.0, 290.0);
        int total = c.length / 2;
        int half = total / 2; // (steps+1) outer points, then (steps+1) inner
        for (int i = 0; i < half; i++) {
            float r = (float) Math.hypot(c[i * 2] - 48f, c[i * 2 + 1] - 50f);
            assertEquals(42f, r, 0.01f);
        }
        for (int i = half; i < total; i++) {
            float r = (float) Math.hypot(c[i * 2] - 66f, c[i * 2 + 1] - 50f);
            assertEquals(34f, r, 0.01f);
        }
    }
}
