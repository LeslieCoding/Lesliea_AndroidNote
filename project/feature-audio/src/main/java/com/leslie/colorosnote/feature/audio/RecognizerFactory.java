package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.io.IOException;

/**
 * Loads the speech model and creates recognizer instances. Production
 * implementation: {@link VoskAndroidBridge}; tests inject fakes.
 */
public interface RecognizerFactory {

    /** Loads the model from a ready model directory (see VoskModelManager). */
    void load(File modelDir) throws IOException;

    boolean isLoaded();

    /** @throws IllegalStateException when the model is not loaded */
    RecognizerAdapter create(float sampleRate);
}
