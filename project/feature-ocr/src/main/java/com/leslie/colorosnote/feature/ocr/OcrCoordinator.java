package com.leslie.colorosnote.feature.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.leslie.colorosnote.core.common.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decision flow for "accurate, not reckless" OCR (spec 14.2):
 *
 * <pre>
 * native text length -> enough? use native text, skip OCR
 *                    -> preprocess -> engine -> sanity/confidence -> index entries
 * </pre>
 *
 * <p>The decision ({@link #plan}) and the post-recognition pipeline
 * ({@link #collect}) are pure JVM so they are unit-testable with fake engines;
 * only {@link #runForIndexing} touches Bitmap/ML Kit.
 */
public final class OcrCoordinator {

    /** Below this many native characters a page is treated as (near-)textless. */
    public static final int MIN_NATIVE_TEXT_CHARS = 16;
    /** Mean element confidence below this marks the whole result low-confidence. */
    public static final float LOW_CONFIDENCE_THRESHOLD = 0.5f;

    private static final String TAG = "OcrCoordinator";

    public enum Plan {
        /** Native text extraction already yielded enough text; do not OCR. */
        USE_NATIVE_TEXT,
        /** Native text absent/too sparse; OCR the rendered page or embedded image. */
        RUN_OCR
    }

    public enum Status {
        SKIPPED_NATIVE_TEXT,
        OK,
        /** Engine returned nothing usable after sanity filtering. */
        EMPTY_RESULT,
        ENGINE_ERROR
    }

    public static final class OcrOutcome {
        public final Status status;
        public final List<OcrIndexEntry> entries;
        /** Result scaled back to original image coordinates; null on skip/error. */
        public final OcrResult result;
        /** True when the result is usable but should rank low in search. */
        public final boolean lowConfidence;
        public final OcrPreprocessor.QualityReport quality; // nullable
        public final OcrException error;                    // nullable

        OcrOutcome(Status status, List<OcrIndexEntry> entries, OcrResult result,
                   boolean lowConfidence, OcrPreprocessor.QualityReport quality,
                   OcrException error) {
            this.status = status;
            this.entries = entries == null
                    ? Collections.emptyList() : Collections.unmodifiableList(entries);
            this.result = result;
            this.lowConfidence = lowConfidence;
            this.quality = quality;
            this.error = error;
        }
    }

    private final OcrEngine engine;
    private final OcrPreprocessor preprocessor;

    public OcrCoordinator(@NonNull OcrEngine engine) {
        this(engine, new OcrPreprocessor());
    }

    public OcrCoordinator(@NonNull OcrEngine engine, @NonNull OcrPreprocessor preprocessor) {
        this.engine = engine;
        this.preprocessor = preprocessor;
    }

    /** Spec 14.2: native text first; only OCR when native text is absent/sparse. */
    public Plan plan(int nativeTextLength) {
        return nativeTextLength >= MIN_NATIVE_TEXT_CHARS ? Plan.USE_NATIVE_TEXT : Plan.RUN_OCR;
    }

    /** Outcome for the native-text-sufficient branch. */
    public OcrOutcome skippedNative() {
        return new OcrOutcome(Status.SKIPPED_NATIVE_TEXT, null, null, false, null, null);
    }

    /**
     * Outcome for an engine failure; never throws, never fabricates text.
     * Intentionally free of logging so it stays safe in pure JVM unit tests
     * (the Android-only {@link #runForIndexing} catch sites do the logging).
     */
    public OcrOutcome engineFailure(@NonNull OcrException e) {
        return new OcrOutcome(Status.ENGINE_ERROR, null, null, false, null, e);
    }

    /**
     * Post-recognition pipeline, pure JVM: scale boxes back to original image
     * coordinates, drop garbage lines, compute the low-confidence flag and build
     * index entries (one per surviving line).
     */
    public OcrOutcome collect(@NonNull OcrResult result,
                              @Nullable OcrPreprocessor.QualityReport quality,
                              @NonNull String documentId, @NonNull String pageId,
                              int originalWidth, int originalHeight) {
        OcrResult scaled = result.scaledTo(originalWidth, originalHeight);
        Float mean = scaled.meanConfidence();
        boolean lowConfidence = mean != null && mean < LOW_CONFIDENCE_THRESHOLD;

        List<OcrIndexEntry> entries = new ArrayList<>();
        for (OcrResult.Block block : scaled.blocks) {
            for (OcrResult.Line line : block.lines) {
                String text = line.text == null ? "" : line.text.trim();
                // Sanity check: lines without any letter/digit are garbage (spec 14.2).
                if (text.isEmpty() || !containsLetterOrDigit(text)) {
                    continue;
                }
                entries.add(new OcrIndexEntry(documentId, pageId, text, line.box,
                        line.confidence, lowConfidence));
            }
        }
        Status status = entries.isEmpty() ? Status.EMPTY_RESULT : Status.OK;
        return new OcrOutcome(status, entries, scaled, lowConfidence, quality, null);
    }

    /**
     * Full orchestration on a rendered page / extracted image. Runs the plan ->
     * preprocess -> recognize -> collect chain; converts any engine failure into
     * an {@link Status#ENGINE_ERROR} outcome instead of crashing (no-GMS devices).
     */
    @WorkerThread
    @NonNull
    public OcrOutcome runForIndexing(@NonNull Bitmap pageImage,
                                     @NonNull String documentId, @NonNull String pageId,
                                     int nativeTextLength, @NonNull OcrOptions options) {
        if (plan(nativeTextLength) == Plan.USE_NATIVE_TEXT) {
            return skippedNative();
        }
        try {
            OcrPreprocessor.PreprocessResult prep = preprocessor.preprocess(pageImage, options);
            OcrResult result = engine.recognize(prep.bitmap, options);
            return collect(result, prep.quality, documentId, pageId,
                    pageImage.getWidth(), pageImage.getHeight());
        } catch (OcrException e) {
            AppLogger.w(TAG, "OCR failed: " + e.code());
            return engineFailure(e);
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "unexpected OCR failure", e);
            return engineFailure(new OcrException(
                    OcrException.ErrorCode.RECOGNITION_FAILED, "unexpected failure", e));
        }
    }

    private static boolean containsLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
