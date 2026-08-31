package com.leslie.colorosnote.feature.audio;

/**
 * Minimal seam over a Vosk recognizer so the transcription pipeline stays
 * JVM-testable without the JNI library. {@link VoskAndroidBridge} provides the
 * production implementation backed by org.vosk.
 */
public interface RecognizerAdapter {

    /**
     * Feeds 16kHz mono PCM16 samples.
     *
     * @return true when an utterance was finalized (silence detected) and
     * {@link #resultJson()} holds a fresh result
     */
    boolean acceptWaveForm(short[] data, int length);

    /** JSON of the last finalized utterance. */
    String resultJson();

    /** JSON of the current in-progress hypothesis. */
    String partialResultJson();

    /** JSON of the final result at stream end. */
    String finalResultJson();

    void close();
}
