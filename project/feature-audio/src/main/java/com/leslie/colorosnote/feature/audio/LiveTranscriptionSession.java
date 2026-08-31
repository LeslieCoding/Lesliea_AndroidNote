package com.leslie.colorosnote.feature.audio;

/**
 * Streaming (live) transcription session core: accumulates finalized
 * utterances and forwards partial hypotheses to the listener. The recognizer
 * is injected, so the accumulation logic is JVM-testable.
 *
 * <p>Threading: {@link #feed(short[], int)} is called from the mic capture
 * thread; {@link #finish()} from the caller's stop path. Listeners fire on the
 * feeding thread — callers post to the main thread themselves.
 */
public final class LiveTranscriptionSession {

    public interface Listener {
        /** In-progress hypothesis (replaces the previous partial). */
        void onPartial(String text);

        /** One utterance finalized; {@link #committedText()} includes it. */
        void onUtteranceFinalized(String text);
    }

    private final RecognizerAdapter recognizer;
    private final Listener listener;
    private final StringBuilder committed = new StringBuilder();
    private boolean finished;

    public LiveTranscriptionSession(RecognizerAdapter recognizer, Listener listener) {
        this.recognizer = recognizer;
        this.listener = listener;
    }

    /** Feeds one chunk of 16kHz mono PCM16. */
    public synchronized void feed(short[] pcm, int length) {
        if (finished || length <= 0) {
            return;
        }
        if (recognizer.acceptWaveForm(pcm, length)) {
            String text = VoskJson.extractText(recognizer.resultJson());
            if (!text.isEmpty()) {
                appendCommitted(text);
                if (listener != null) {
                    listener.onUtteranceFinalized(text);
                }
            }
        } else if (listener != null) {
            listener.onPartial(VoskJson.extractPartial(recognizer.partialResultJson()));
        }
    }

    /** Flushes the recognizer tail and returns the full transcript. */
    public synchronized String finish() {
        if (!finished) {
            finished = true;
            String tail = VoskJson.extractText(recognizer.finalResultJson());
            if (!tail.isEmpty()) {
                appendCommitted(tail);
                if (listener != null) {
                    listener.onUtteranceFinalized(tail);
                }
            }
        }
        return committedText();
    }

    /** All finalized text so far (partials not included). */
    public synchronized String committedText() {
        return committed.toString();
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    private void appendCommitted(String text) {
        if (committed.length() > 0) {
            committed.append('，');
        }
        committed.append(text);
    }
}
