package com.leslie.colorosnote.feature.ocr;

/** Options for one OCR run. Immutable. */
public final class OcrOptions {

    public static final int DEFAULT_MAX_LONG_EDGE = 2048;

    /** BCP-47-ish language hint, e.g. "zh"; null = engine default (Chinese model). */
    public final String languageHint;
    /** Long-edge pixel limit applied by preprocessing (spec 14.1 smart scaling). */
    public final int maxLongEdge;
    /** Whether to grayscale + contrast-enhance before recognition. */
    public final boolean enhanceContrast;
    /** Whether to deskew before recognition. */
    public final boolean deskew;

    public OcrOptions(String languageHint, int maxLongEdge, boolean enhanceContrast, boolean deskew) {
        this.languageHint = languageHint;
        this.maxLongEdge = maxLongEdge <= 0 ? DEFAULT_MAX_LONG_EDGE : maxLongEdge;
        this.enhanceContrast = enhanceContrast;
        this.deskew = deskew;
    }

    public static OcrOptions defaults() {
        return new OcrOptions(null, DEFAULT_MAX_LONG_EDGE, true, true);
    }
}
