package com.leslie.colorosnote.core.ink;

import android.view.MotionEvent;
import android.view.View;

import com.leslie.colorosnote.core.common.AppLogger;

/**
 * Thin isolation wrapper around androidx.input's MotionEventPredictor
 * (requirement 5.1). The androidx class is accessed reflectively so the ink
 * engine keeps working — just without prediction — when the library is
 * missing, stripped, or behaves badly on a given device/ROM. All calls are
 * no-throw.
 */
public interface MotionPredictor {

    /** Feeds a real touch event into the predictor. */
    void record(MotionEvent event);

    /**
     * Returns a predicted event (may carry batched predicted samples), or
     * null when prediction is unavailable.
     */
    MotionEvent predict();

    /**
     * Creates a predictor for the given view, falling back to a no-op
     * implementation when androidx.input is unusable.
     */
    static MotionPredictor create(View view) {
        try {
            return new AndroidXMotionPredictor(view);
        } catch (Throwable t) {
            AppLogger.w("MotionPredictor", "androidx.input prediction unavailable, fallback to none");
            return new NoOp();
        }
    }

    /** Fallback used when prediction cannot be provided. */
    final class NoOp implements MotionPredictor {
        @Override
        public void record(MotionEvent event) {
        }

        @Override
        public MotionEvent predict() {
            return null;
        }
    }
}

/**
 * Reflective bridge to androidx.input.prediction.MotionEventPredictor so the
 * compile-time surface of core-ink never hard-depends on the library's API.
 */
final class AndroidXMotionPredictor implements MotionPredictor {

    // Package is androidx.input.motionprediction — NOT androidx.input.prediction.
    // A wrong name here fails silently (Class.forName throws, we fall back to
    // NoOp), so MotionPredictorClassNameTest pins it against the real artifact.
    static final String CLASS_NAME = "androidx.input.motionprediction.MotionEventPredictor";

    private final Object predictor;
    private final java.lang.reflect.Method recordMethod;
    private final java.lang.reflect.Method predictMethod;

    AndroidXMotionPredictor(View view) throws Exception {
        Class<?> cls = Class.forName(CLASS_NAME);
        predictor = cls.getMethod("newInstance", View.class).invoke(null, view);
        if (predictor == null) {
            throw new IllegalStateException("MotionEventPredictor.newInstance returned null");
        }
        recordMethod = cls.getMethod("record", MotionEvent.class);
        predictMethod = cls.getMethod("predict");
    }

    @Override
    public void record(MotionEvent event) {
        try {
            recordMethod.invoke(predictor, event);
        } catch (Throwable ignored) {
            // Prediction must never break the input pipeline.
        }
    }

    @Override
    public MotionEvent predict() {
        try {
            return (MotionEvent) predictMethod.invoke(predictor);
        } catch (Throwable t) {
            return null;
        }
    }
}
