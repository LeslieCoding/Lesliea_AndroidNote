package com.leslie.colorosnote.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the background template enum and factory. */
public class PageBackgroundTest {

    @Test
    public void ofTypeSetsTypeAndKeepsDefaults() {
        PageBackground bg = PageBackground.ofType(PageBackground.Type.MUSIC);
        assertEquals(PageBackground.Type.MUSIC, bg.type);
        assertTrue(bg.templateParams.isEmpty());
    }

    @Test
    public void ofTypeCoversEveryNewTemplate() {
        PageBackground.Type[] newTypes = {
                PageBackground.Type.CORNELL_GRID, PageBackground.Type.ENGLISH,
                PageBackground.Type.TIANZI, PageBackground.Type.MIZI,
                PageBackground.Type.MUSIC, PageBackground.Type.TODO,
                PageBackground.Type.DAILY, PageBackground.Type.WEEKLY,
                PageBackground.Type.MEETING};
        for (PageBackground.Type type : newTypes) {
            assertSame(type, PageBackground.ofType(type).type);
        }
    }

    /**
     * Serialization uses enum names; document backgrounds keep their relative
     * order (after the nine templates added in 1.4.0) so stored ordinals in
     * caches stay meaningful.
     */
    @Test
    public void documentBackgroundsKeepTheirOrdinalBlock() {
        PageBackground.Type[] values = PageBackground.Type.values();
        assertEquals(18, values.length);
        assertEquals(PageBackground.Type.PDF, values[14]);
        assertEquals(PageBackground.Type.OFFICE, values[15]);
        assertEquals(PageBackground.Type.IMAGE, values[16]);
        assertEquals(PageBackground.Type.CUSTOM, values[17]);
    }

    @Test
    public void newTemplatesOccupyTheCornellToPdfRange() {
        PageBackground.Type[] values = PageBackground.Type.values();
        assertEquals(PageBackground.Type.CORNELL, values[4]);
        assertEquals(PageBackground.Type.CORNELL_GRID, values[5]);
        assertEquals(PageBackground.Type.ENGLISH, values[6]);
        assertEquals(PageBackground.Type.TIANZI, values[7]);
        assertEquals(PageBackground.Type.MIZI, values[8]);
        assertEquals(PageBackground.Type.MUSIC, values[9]);
        assertEquals(PageBackground.Type.TODO, values[10]);
        assertEquals(PageBackground.Type.DAILY, values[11]);
        assertEquals(PageBackground.Type.WEEKLY, values[12]);
        assertEquals(PageBackground.Type.MEETING, values[13]);
    }

    @Test
    public void namesRoundTripThroughValueOf() {
        for (PageBackground.Type type : PageBackground.Type.values()) {
            assertSame(type, PageBackground.Type.valueOf(type.name()));
        }
    }
}
