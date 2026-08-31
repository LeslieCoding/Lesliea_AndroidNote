package com.leslie.colorosnote.feature.audio;

import java.io.File;
import java.util.List;

/**
 * whisper.cpp-based on-device transcription engine skeleton (spec 21.1).
 * <p>
 * Model file management (download/size/delete) is fully implemented via
 * {@link ModelManager}. The actual JNI bridge to whisper.cpp requires an NDK
 * build of the whisper library, an external dependency not bundled in this
 * project; until a native bridge is registered, transcription returns honest
 * error codes instead of fabricating results (spec: 禁止伪实现).
 */
public final class WhisperTranscriptionEngine implements TranscriptionEngine {

    /** Optional native bridge; set when the whisper.cpp JNI wrapper is present. */
    public interface NativeBridge {
        List<TranscriptSegment> transcribePcm(File audioFile, File modelFile, CancelToken cancel);
    }

    private final ModelManager.ModelInfo model;
    private NativeBridge bridge; // nullable

    public WhisperTranscriptionEngine(ModelManager.ModelInfo model) {
        this.model = model;
    }

    public void setNativeBridge(NativeBridge bridge) {
        this.bridge = bridge;
    }

    public ModelManager.ModelInfo modelInfo() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        return bridge != null && model.localFile.exists();
    }

    @Override
    public TranscriptionResult transcribe(File audio, ProgressCallback progress, CancelToken cancel) {
        if (!model.localFile.exists()) {
            return TranscriptionResult.error(ErrorCode.MODEL_NOT_INSTALLED,
                    "ASR model not downloaded; download it from Settings > Transcription");
        }
        if (bridge == null) {
            return TranscriptionResult.error(ErrorCode.ENGINE_UNAVAILABLE,
                    "whisper.cpp native bridge not bundled in this build");
        }
        if (progress != null) progress.onProgress(0, "transcribing");
        List<TranscriptSegment> out = bridge.transcribePcm(audio, model.localFile, cancel);
        if (cancel != null && cancel.isCancelled()) {
            return TranscriptionResult.error(ErrorCode.CANCELLED, "cancelled");
        }
        if (progress != null) progress.onProgress(100, "done");
        return TranscriptionResult.ok(out);
    }

    @Override
    public String displayName() {
        return "On-device (whisper)";
    }
}
