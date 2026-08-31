package com.leslie.colorosnote.feature.handwriting;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.List;

/**
 * Handwriting recognizer abstraction (spec 15). Blocking worker-thread API;
 * callers schedule on {@code AppExecutors.get().background()}.
 *
 * <p>Implementations must never fabricate candidates: on failure they throw
 * {@link HandwritingException} with a precise error code.
 */
public interface HandwritingRecognizer {

    /**
     * Recognizes the given strokes (in writing order) into text candidates,
     * best candidate first.
     *
     * @throws HandwritingException MODEL_NOT_DOWNLOADED when the language model
     *         is missing, ENGINE_UNAVAILABLE on devices without GMS
     */
    @WorkerThread
    @NonNull
    List<Candidate> recognize(@NonNull List<StrokeData> strokes,
                              @NonNull RecognizeOptions options) throws HandwritingException;

    /** True when the default (Chinese) model is present on device. */
    @WorkerThread
    boolean isModelDownloaded() throws HandwritingException;

    /** Downloads the default (Chinese) model; blocks until done. */
    @WorkerThread
    void downloadModel() throws HandwritingException;

    /** Deletes the default (Chinese) model from device. */
    @WorkerThread
    void deleteModel() throws HandwritingException;

    /** Releases recognizer clients. Idempotent. */
    @WorkerThread
    void close();
}
