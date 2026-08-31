package com.leslie.colorosnote.feature.editor;

import java.io.File;

/**
 * Default {@link SpeechTranscriptionGateway} used until a real transcription
 * engine is wired in: reports unavailable and fails fast. The UI still shows
 * the 转文字 affordance and tells the user the engine is not ready.
 */
public final class UnavailableSpeechTranscriptionGateway implements SpeechTranscriptionGateway {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void transcribe(File audioFile, Callback callback) {
        callback.onError("transcription engine not installed");
    }
}
