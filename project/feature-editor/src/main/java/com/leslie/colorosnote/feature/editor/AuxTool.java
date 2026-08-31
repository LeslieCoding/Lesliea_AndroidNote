package com.leslie.colorosnote.feature.editor;

/**
 * Auxiliary toolbar entries (spec 28.4). The toolbar and dispatch framework are
 * real; object insertion/editing panels (IMAGE/TEXT_BOX/SHAPE/TAPE/STICKER)
 * and OCR/handwriting UIs are implemented by the follow-up agent — register an
 * {@link AuxToolListener} on the fragment to receive these events.
 */
public enum AuxTool {
    IMAGE,
    TEXT_BOX,
    SHAPE,
    TAPE,
    STICKER,
    OCR,
    HANDWRITING_TO_TEXT,
    RECORDING,
    SPEECH_TO_TEXT,
    PAGE_TOOLS;

    /** Receives aux toolbar selections. */
    public interface AuxToolListener {
        void onAuxToolSelected(AuxTool tool);
    }
}
