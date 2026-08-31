package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.util.List;

/**
 * Two-layer transcription abstraction (spec 21): on-device ASR is the default;
 * cloud providers are optional, user-configured, off by default.
 */
public interface TranscriptionEngine {

    enum ErrorCode {
        NONE, MODEL_NOT_INSTALLED, ENGINE_UNAVAILABLE, CANCELLED, IO_ERROR, NETWORK_ERROR
    }

    interface ProgressCallback {
        /** 0..100 */
        void onProgress(int percent, String stage);
    }

    interface CancelToken {
        boolean isCancelled();
    }

    final class TranscriptionResult {
        public final ErrorCode error;
        public final String errorMessage;
        public final List<TranscriptSegment> segments;

        private TranscriptionResult(ErrorCode error, String errorMessage,
                                    List<TranscriptSegment> segments) {
            this.error = error;
            this.errorMessage = errorMessage;
            this.segments = segments;
        }

        public static TranscriptionResult ok(List<TranscriptSegment> segments) {
            return new TranscriptionResult(ErrorCode.NONE, null, segments);
        }

        public static TranscriptionResult error(ErrorCode code, String message) {
            return new TranscriptionResult(code, message, null);
        }
    }

    /** True when the engine can transcribe right now (model present etc.). */
    boolean isAvailable();

    /**
     * Transcribe an audio file. Must run off the main thread; must honor the
     * cancel token; must support pause via cooperative chunking.
     */
    TranscriptionResult transcribe(File audio, ProgressCallback progress, CancelToken cancel);

    /** Human-readable name for UI. */
    String displayName();
}
