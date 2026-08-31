package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.leslie.colorosnote.feature.audio.MediaCodecAudioDecoder;
import com.leslie.colorosnote.feature.audio.RecognizerFactory;
import com.leslie.colorosnote.feature.audio.TranscriptionEngine;
import com.leslie.colorosnote.feature.audio.VoskAndroidBridge;
import com.leslie.colorosnote.feature.audio.VoskLiveTranscriber;
import com.leslie.colorosnote.feature.audio.VoskModelManager;
import com.leslie.colorosnote.feature.audio.VoskTranscriptionEngine;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real {@link SpeechTranscriptionGateway} backed by the Vosk offline engine
 * (feature-audio). Also the shared access point for the speech model: both the
 * recording 转文字 flow and the 语音转文字 dialog go through this gateway's
 * {@link #modelManager()} / {@link #recognizerFactory()}.
 *
 * <p>{@link #isAvailable()} is true only when the 42MB Chinese model is
 * installed ({@link VoskModelManager#isModelReady()}); otherwise call sites
 * should offer {@link SpeechModelDownloadDialog} first. Transcription runs on
 * a single background executor; callbacks are posted to the main thread.
 */
public final class VoskSpeechTranscriptionGateway implements SpeechTranscriptionGateway {

    /** Model download result, delivered on the main thread. */
    public interface ModelCallback {
        void onModelReady();

        void onModelError(String message);
    }

    private final VoskModelManager modelManager;
    private final VoskAndroidBridge bridge;
    private final VoskTranscriptionEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean downloading;

    public VoskSpeechTranscriptionGateway(Context context) {
        modelManager = new VoskModelManager(context.getFilesDir());
        bridge = new VoskAndroidBridge();
        engine = new VoskTranscriptionEngine(modelManager, bridge, new MediaCodecAudioDecoder());
    }

    @Override
    public boolean isAvailable() {
        return modelManager.isModelReady();
    }

    public VoskModelManager modelManager() {
        return modelManager;
    }

    /** Shared model bridge (recognizer factory) for live transcription. */
    public RecognizerFactory recognizerFactory() {
        return bridge;
    }

    /** Creates a live (mic streaming) transcriber bound to this gateway. */
    public VoskLiveTranscriber newLiveTranscriber(VoskLiveTranscriber.Listener listener) {
        return new VoskLiveTranscriber(modelManager, bridge, listener);
    }

    /** True while a model download is in flight (prevents duplicate downloads). */
    public boolean isDownloading() {
        return downloading;
    }

    /**
     * Downloads + extracts the speech model when missing (no-op when ready).
     * Progress 0..100 fires on the main thread.
     */
    public void ensureModel(VoskModelManager.ProgressCallback progress, ModelCallback callback) {
        if (modelManager.isModelReady()) {
            callback.onModelReady();
            return;
        }
        if (downloading) {
            callback.onModelError("模型正在下载中，请稍候");
            return;
        }
        downloading = true;
        io.execute(() -> {
            try {
                modelManager.ensureModel((percent, stage) ->
                        main.post(() -> {
                            if (progress != null) progress.onProgress(percent, stage);
                        }));
                main.post(callback::onModelReady);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "模型下载失败" : e.getMessage();
                main.post(() -> callback.onModelError(message));
            } finally {
                downloading = false;
            }
        });
    }

    @Override
    public void transcribe(File audioFile, Callback callback) {
        io.execute(() -> {
            TranscriptionEngine.TranscriptionResult result =
                    engine.transcribe(audioFile, null, null);
            if (result.error == TranscriptionEngine.ErrorCode.NONE) {
                String text = VoskTranscriptionEngine.joinText(result.segments);
                main.post(() -> callback.onTranscript(text));
            } else {
                String message = result.errorMessage == null ? "识别失败" : result.errorMessage;
                main.post(() -> callback.onError(message));
            }
        });
    }
}
