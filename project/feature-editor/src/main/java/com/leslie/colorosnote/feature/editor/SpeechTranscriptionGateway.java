package com.leslie.colorosnote.feature.editor;

import java.io.File;

/**
 * Gateway to a speech-to-text engine for recorded audio (spec 20.3).
 *
 * <p>Injection point: {@link EditorServices#speechTranscription()} — wired to
 * the Vosk offline engine ({@link VoskSpeechTranscriptionGateway});
 * {@link UnavailableSpeechTranscriptionGateway} remains as a no-engine
 * placeholder for tests/fallback.
 */
public interface SpeechTranscriptionGateway {

    /** True when an engine is installed/loaded and {@link #transcribe} can run. */
    boolean isAvailable();

    /**
     * Transcribes one audio file (a recording segment, AAC/M4A). Must invoke
     * the callback exactly once, on the main thread.
     */
    void transcribe(File audioFile, Callback callback);

    interface Callback {
        void onTranscript(String text);

        void onError(String message);
    }
}
