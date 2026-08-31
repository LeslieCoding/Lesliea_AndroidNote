package com.leslie.colorosnote.feature.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

/**
 * OCR engine abstraction (spec 14.1). Implementations are blocking; callers
 * must run them off the main thread, typically on
 * {@code AppExecutors.get().background()} so OCR never competes with the
 * real-time ink pipeline.
 *
 * <p>Engines must never return fabricated text: when recognition is impossible
 * (no GMS, model missing, decode failure) they throw {@link OcrException}.
 */
public interface OcrEngine {

    /**
     * Recognizes text in the given bitmap. Coordinates in the result are relative
     * to {@code bitmap} itself; the engine records its dimensions in the result.
     *
     * @throws OcrException with {@link OcrException.ErrorCode#ENGINE_UNAVAILABLE}
     *         when the engine/GMS is unavailable, never crashes on such devices
     */
    @WorkerThread
    @NonNull
    OcrResult recognize(@NonNull Bitmap bitmap, @NonNull OcrOptions options) throws OcrException;

    /** Cheap best-effort availability check; false when the engine is known unusable. */
    boolean isAvailable();

    /** Releases engine resources. Idempotent. */
    @WorkerThread
    void close();
}
