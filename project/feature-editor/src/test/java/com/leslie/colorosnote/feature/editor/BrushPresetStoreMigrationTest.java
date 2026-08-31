package com.leslie.colorosnote.feature.editor;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Preset-defaults migration acceptance (v2.0.0: PRESET_VERSION 5). */
public class BrushPresetStoreMigrationTest {

    @Test
    public void currentAndV4V3PresetsLoadForAllTools() {
        for (EditorTool tool : new EditorTool[]{EditorTool.FOUNTAIN_PEN,
                EditorTool.BALLPOINT, EditorTool.PENCIL, EditorTool.HIGHLIGHTER}) {
            assertTrue(tool.name(), BrushPresetStore.acceptsPresetVersion(5, tool));
            // v2.0.0: existing user tuning (v4 written by v1.4.21, v3 by
            // older releases) must survive the defaults refresh.
            assertTrue(tool.name(), BrushPresetStore.acceptsPresetVersion(4, tool));
            assertTrue(tool.name(), BrushPresetStore.acceptsPresetVersion(3, tool));
        }
    }

    @Test
    public void v2LoadsExceptBallpointAndPencil() {
        // v1.4.16 rule: v2 ballpoint/pencil presets encode the removed taper
        // behavior and stay dropped.
        assertTrue(BrushPresetStore.acceptsPresetVersion(2, EditorTool.FOUNTAIN_PEN));
        assertTrue(BrushPresetStore.acceptsPresetVersion(2, EditorTool.HIGHLIGHTER));
        assertFalse(BrushPresetStore.acceptsPresetVersion(2, EditorTool.BALLPOINT));
        assertFalse(BrushPresetStore.acceptsPresetVersion(2, EditorTool.PENCIL));
    }

    @Test
    public void ancientOrMissingVersionsAreDropped() {
        for (EditorTool tool : new EditorTool[]{EditorTool.FOUNTAIN_PEN,
                EditorTool.BALLPOINT, EditorTool.PENCIL, EditorTool.HIGHLIGHTER}) {
            assertFalse(tool.name(), BrushPresetStore.acceptsPresetVersion(1, tool));
            assertFalse(tool.name(), BrushPresetStore.acceptsPresetVersion(0, tool));
        }
    }
}
