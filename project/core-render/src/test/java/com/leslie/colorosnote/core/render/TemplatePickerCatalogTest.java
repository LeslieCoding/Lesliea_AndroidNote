package com.leslie.colorosnote.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.leslie.colorosnote.core.model.PageBackground;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure-JVM tests for the picker's static catalog: the TYPES/LABELS arrays are
 * plain static data, so they load fine without an Android runtime.
 */
public class TemplatePickerCatalogTest {

    @Test
    public void catalogListsAllFourteenSwitchableTemplates() {
        assertEquals(14, TemplatePickerDialog.TYPES.length);
        assertEquals(TemplatePickerDialog.TYPES.length, TemplatePickerDialog.LABELS.length);
    }

    @Test
    public void catalogHasNoDuplicates() {
        Set<PageBackground.Type> seen = new HashSet<>();
        for (PageBackground.Type type : TemplatePickerDialog.TYPES) {
            assertFalse("duplicate " + type, seen.contains(type));
            seen.add(type);
        }
    }

    @Test
    public void catalogExcludesDocumentAndImageBackgrounds() {
        for (PageBackground.Type type : TemplatePickerDialog.TYPES) {
            assertFalse(type == PageBackground.Type.PDF
                    || type == PageBackground.Type.OFFICE
                    || type == PageBackground.Type.IMAGE
                    || type == PageBackground.Type.CUSTOM);
        }
    }
}
