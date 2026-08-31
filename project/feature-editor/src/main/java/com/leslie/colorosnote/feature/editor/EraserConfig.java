package com.leslie.colorosnote.feature.editor;

import com.leslie.colorosnote.core.ink.EraserEngine;

/** Eraser tool configuration, persisted by {@link BrushPresetStore}. */
public final class EraserConfig {

    public EraserEngine.Mode mode = EraserEngine.Mode.PIXEL;
    public EraserEngine.TypeFilter filter = EraserEngine.TypeFilter.ALL;
    /** Eraser radius in logical page units. */
    public float radius = 16f;

    public EraserConfig copy() {
        EraserConfig c = new EraserConfig();
        c.mode = mode;
        c.filter = filter;
        c.radius = radius;
        return c;
    }
}
