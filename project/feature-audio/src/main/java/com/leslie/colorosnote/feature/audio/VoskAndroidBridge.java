package com.leslie.colorosnote.feature.audio;

import com.leslie.colorosnote.core.common.AppLogger;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;

/**
 * Production {@link RecognizerFactory} backed by the Vosk JNI library
 * (org.vosk). The {@link Model} load is heavy (seconds, ~300MB RAM at
 * recognition time for the small-cn model), so it is cached and reused across
 * recognizers; call sites must run {@link #load(File)} off the main thread.
 */
public final class VoskAndroidBridge implements RecognizerFactory {

    private static final String TAG = "VoskAndroidBridge";

    private Model model;

    public VoskAndroidBridge() {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
    }

    @Override
    public synchronized void load(File modelDir) throws IOException {
        if (model != null) {
            return;
        }
        try {
            model = new Model(modelDir.getAbsolutePath());
        } catch (IOException e) {
            AppLogger.e(TAG, "vosk model load failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public synchronized boolean isLoaded() {
        return model != null;
    }

    /** Releases the native model (settings "delete model" path). */
    public synchronized void unload() {
        if (model != null) {
            model.close();
            model = null;
        }
    }

    @Override
    public RecognizerAdapter create(float sampleRate) {
        final Model m;
        synchronized (this) {
            if (model == null) {
                throw new IllegalStateException("vosk model not loaded");
            }
            m = model;
        }
        final Recognizer recognizer;
        try {
            recognizer = new Recognizer(m, sampleRate);
        } catch (IOException e) {
            throw new IllegalStateException("创建识别器失败：" + e.getMessage(), e);
        }
        return new RecognizerAdapter() {
            @Override
            public boolean acceptWaveForm(short[] data, int length) {
                return recognizer.acceptWaveForm(data, length);
            }

            @Override
            public String resultJson() {
                return recognizer.getResult();
            }

            @Override
            public String partialResultJson() {
                return recognizer.getPartialResult();
            }

            @Override
            public String finalResultJson() {
                return recognizer.getFinalResult();
            }

            @Override
            public void close() {
                recognizer.close();
            }
        };
    }
}
