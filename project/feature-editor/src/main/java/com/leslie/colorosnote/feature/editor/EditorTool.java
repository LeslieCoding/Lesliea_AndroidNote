package com.leslie.colorosnote.feature.editor;

/** Core toolbar tools (spec 28.4). Pen-like tools map to a BrushPreset each. */
public enum EditorTool {
    FOUNTAIN_PEN,
    BALLPOINT,
    PENCIL,
    HIGHLIGHTER,
    ERASER,
    LASSO;

    public boolean isPen() {
        return this == FOUNTAIN_PEN || this == BALLPOINT
                || this == PENCIL || this == HIGHLIGHTER;
    }
}
