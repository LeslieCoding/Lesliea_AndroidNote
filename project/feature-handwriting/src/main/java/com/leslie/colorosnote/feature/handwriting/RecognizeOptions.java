package com.leslie.colorosnote.feature.handwriting;

/** Options for one handwriting recognition run (spec 15). Immutable. */
public final class RecognizeOptions {

    public static final String DEFAULT_LANGUAGE_TAG = "zh-Hani";
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** BCP-47 language tag for the recognizer model; default simplified Chinese. */
    public final String languageTag;
    /** Upper bound on returned candidates (applied client-side). */
    public final int maxResultCount;
    /** Text immediately before the writing area, improves accuracy; nullable. */
    public final String preContext;
    /** Writing area size in the same units as stroke points; <= 0 means unset. */
    public final float writingAreaWidth;
    public final float writingAreaHeight;

    public RecognizeOptions(String languageTag, int maxResultCount, String preContext,
                            float writingAreaWidth, float writingAreaHeight) {
        this.languageTag = languageTag == null || languageTag.isEmpty()
                ? DEFAULT_LANGUAGE_TAG : languageTag;
        this.maxResultCount = maxResultCount <= 0 ? DEFAULT_MAX_RESULTS : maxResultCount;
        this.preContext = preContext;
        this.writingAreaWidth = writingAreaWidth;
        this.writingAreaHeight = writingAreaHeight;
    }

    public static RecognizeOptions defaults() {
        return new RecognizeOptions(null, DEFAULT_MAX_RESULTS, null, 0, 0);
    }
}
