package com.leslie.colorosnote.feature.audio;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates live speech-to-text: model load + {@link MicCapture} +
 * {@link LiveTranscriptionSession}. All listener callbacks are posted to the
 * main thread; capture and recognition run on background threads.
 *
 * <p>Lifecycle: {@link #start()} → {@link #setPaused(boolean)} →
 * {@link #stopAndFinish()}. A stopped transcriber can be started again (fresh
 * recognizer, text reset).
 */
public final class VoskLiveTranscriber {

    public enum State {IDLE, LOADING, RECORDING, PAUSED, FINISHING, FAILED}

    /** All callbacks fire on the main thread. */
    public interface Listener {
        void onStateChanged(State state);

        /** Latest in-progress hypothesis ("" when a new utterance started). */
        void onPartial(String text);

        /** Full committed text changed (utterance finalized). */
        void onCommittedText(String fullText);

        void onError(String message);
    }

    private final VoskModelManager modelManager;
    private final RecognizerFactory recognizerFactory;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private State state = State.IDLE;
    private MicCapture capture;
    private LiveTranscriptionSession session;
    private String lastPartial = "";

    public VoskLiveTranscriber(VoskModelManager modelManager,
                               RecognizerFactory recognizerFactory,
                               Listener listener) {
        this.modelManager = modelManager;
        this.recognizerFactory = recognizerFactory;
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    /** True when the model exists on disk; otherwise start() fails fast. */
    public boolean isModelReady() {
        return modelManager.isModelReady();
    }

    /** Starts capture; loads the model on a worker thread first. */
    public void start() {
        if (state != State.IDLE && state != State.FAILED) {
            return;
        }
        setState(State.LOADING);
        worker.execute(() -> {
            try {
                if (!recognizerFactory.isLoaded()) {
                    recognizerFactory.load(modelManager.modelDir());
                }
            } catch (IOException | RuntimeException e) {
                postError("识别模型加载失败：" + e.getMessage());
                setState(State.FAILED);
                return;
            }
            session = new LiveTranscriptionSession(
                    recognizerFactory.create(PcmResampler.TARGET_RATE),
                    new LiveTranscriptionSession.Listener() {
                        @Override
                        public void onPartial(String text) {
                            lastPartial = text;
                            main.post(() -> listener.onPartial(text));
                        }

                        @Override
                        public void onUtteranceFinalized(String text) {
                            lastPartial = "";
                            String full = session == null ? text : session.committedText();
                            main.post(() -> listener.onCommittedText(full));
                        }
                    });
            capture = new MicCapture(new MicCapture.PcmListener() {
                @Override
                public void onPcm(short[] data, int length) {
                    LiveTranscriptionSession s = session;
                    if (s != null) {
                        s.feed(data, length);
                    }
                }

                @Override
                public void onError(String message) {
                    postError(message);
                    setState(State.FAILED);
                }
            });
            capture.start();
            if (!capture.isRunning()) {
                return; // onError already reported and moved state to FAILED
            }
            setState(State.RECORDING);
        });
    }

    /** Pause/resume capture; committed text is kept across a pause. */
    public void setPaused(boolean paused) {
        if (state != State.RECORDING && state != State.PAUSED) {
            return;
        }
        MicCapture c = capture;
        if (c != null) {
            c.setPaused(paused);
        }
        setState(paused ? State.PAUSED : State.RECORDING);
    }

    /** Stops capture and flushes the recognizer tail; result via onCommittedText. */
    public void stopAndFinish() {
        if (state != State.RECORDING && state != State.PAUSED) {
            return;
        }
        setState(State.FINISHING);
        worker.execute(() -> {
            MicCapture c = capture;
            capture = null;
            if (c != null) {
                c.stop();
            }
            LiveTranscriptionSession s = session;
            String full = s == null ? "" : s.finish();
            main.post(() -> {
                listener.onCommittedText(full);
                setState(State.IDLE);
            });
        });
    }

    /** Current display text: committed + in-progress hypothesis. */
    public String currentText() {
        LiveTranscriptionSession s = session;
        String committed = s == null ? "" : s.committedText();
        if (lastPartial.isEmpty()) {
            return committed;
        }
        return committed.isEmpty() ? lastPartial : committed + " " + lastPartial;
    }

    /** Releases the worker; call from the host's teardown. */
    public void release() {
        MicCapture c = capture;
        capture = null;
        if (c != null) {
            c.stop();
        }
        worker.shutdownNow();
    }

    private void setState(State newState) {
        state = newState;
        main.post(() -> listener.onStateChanged(newState));
    }

    private void postError(String message) {
        main.post(() -> listener.onError(message));
    }
}
