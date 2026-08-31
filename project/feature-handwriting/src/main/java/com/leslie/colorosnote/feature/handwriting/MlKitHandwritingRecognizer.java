package com.leslie.colorosnote.feature.handwriting;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionCandidate;
import com.google.mlkit.vision.digitalink.RecognitionContext;
import com.google.mlkit.vision.digitalink.RecognitionResult;
import com.google.mlkit.vision.digitalink.WritingArea;
import com.leslie.colorosnote.core.common.AppLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * ML Kit Digital Ink Recognition with the Chinese model (spec 15).
 *
 * <p>Blocking wrapper around the Task-based API. Model lifecycle (query /
 * download / delete) goes through {@link RemoteModelManager}. Devices without
 * GMS surface typed {@link HandwritingException}s instead of crashing.
 */
public final class MlKitHandwritingRecognizer implements HandwritingRecognizer {

    private static final String TAG = "MlKitHandwriting";

    private final Object lock = new Object();
    private final Map<String, DigitalInkRecognizer> recognizers = new HashMap<>();
    private final RemoteModelManager modelManager;

    public MlKitHandwritingRecognizer() {
        this(RemoteModelManager.getInstance());
    }

    /** Test seam / DI point for the model manager. */
    MlKitHandwritingRecognizer(RemoteModelManager modelManager) {
        this.modelManager = modelManager;
    }

    @Override
    @WorkerThread
    @NonNull
    public List<Candidate> recognize(@NonNull List<StrokeData> strokes,
                                     @NonNull RecognizeOptions options)
            throws HandwritingException {
        if (strokes.isEmpty()) {
            throw new HandwritingException(
                    HandwritingException.ErrorCode.INVALID_INPUT, "no strokes");
        }
        DigitalInkRecognitionModel model = modelFor(options.languageTag);
        if (!isModelDownloaded(model)) {
            throw new HandwritingException(HandwritingException.ErrorCode.MODEL_NOT_DOWNLOADED,
                    "model not downloaded: " + options.languageTag);
        }

        Ink.Builder inkBuilder = Ink.builder();
        for (StrokeData s : strokes) {
            Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
            for (int i = 0; i < s.pointCount(); i++) {
                strokeBuilder.addPoint(Ink.Point.create(s.xs[i], s.ys[i], s.ts[i]));
            }
            inkBuilder.addStroke(strokeBuilder.build());
        }

        RecognitionContext.Builder contextBuilder = RecognitionContext.builder();
        // ML Kit refuses to build a RecognitionContext without a preContext
        // ("Missing required properties: preContext"); empty string is the
        // documented no-context value.
        contextBuilder.setPreContext(
                options.preContext == null ? "" : options.preContext);
        if (options.writingAreaWidth > 0 && options.writingAreaHeight > 0) {
            contextBuilder.setWritingArea(
                    new WritingArea(options.writingAreaWidth, options.writingAreaHeight));
        }

        RecognitionResult result;
        try {
            result = Tasks.await(recognizerFor(model)
                    .recognize(inkBuilder.build(), contextBuilder.build()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HandwritingException(
                    HandwritingException.ErrorCode.RECOGNITION_FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            throw mapFailure(e.getCause());
        } catch (RuntimeException e) {
            throw mapFailure(e);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (RecognitionCandidate rc : result.getCandidates()) {
            candidates.add(new Candidate(rc.getText(), rc.getScore()));
            if (candidates.size() >= options.maxResultCount) {
                break;
            }
        }
        return candidates;
    }

    @Override
    @WorkerThread
    public boolean isModelDownloaded() throws HandwritingException {
        return isModelDownloaded(modelFor(RecognizeOptions.DEFAULT_LANGUAGE_TAG));
    }

    @Override
    @WorkerThread
    public void downloadModel() throws HandwritingException {
        DigitalInkRecognitionModel model = modelFor(RecognizeOptions.DEFAULT_LANGUAGE_TAG);
        try {
            Tasks.await(modelManager.download(model, new DownloadConditions.Builder().build()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HandwritingException(
                    HandwritingException.ErrorCode.RECOGNITION_FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            throw mapFailure(e.getCause());
        } catch (RuntimeException e) {
            throw mapFailure(e);
        }
    }

    @Override
    @WorkerThread
    public void deleteModel() throws HandwritingException {
        DigitalInkRecognitionModel model = modelFor(RecognizeOptions.DEFAULT_LANGUAGE_TAG);
        try {
            Tasks.await(modelManager.deleteDownloadedModel(model));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HandwritingException(
                    HandwritingException.ErrorCode.RECOGNITION_FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            throw mapFailure(e.getCause());
        } catch (RuntimeException e) {
            throw mapFailure(e);
        }
    }

    @Override
    @WorkerThread
    public void close() {
        synchronized (lock) {
            for (DigitalInkRecognizer r : recognizers.values()) {
                r.close();
            }
            recognizers.clear();
        }
    }

    private boolean isModelDownloaded(DigitalInkRecognitionModel model)
            throws HandwritingException {
        try {
            return Tasks.await(modelManager.isModelDownloaded(model));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HandwritingException(
                    HandwritingException.ErrorCode.RECOGNITION_FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            throw mapFailure(e.getCause());
        } catch (RuntimeException e) {
            throw mapFailure(e);
        }
    }

    private DigitalInkRecognitionModel modelFor(String languageTag) throws HandwritingException {
        DigitalInkRecognitionModelIdentifier identifier;
        try {
            // Checked MlKitException: tag lookup goes through the GMS registry.
            identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag);
        } catch (MlKitException e) {
            throw new HandwritingException(HandwritingException.ErrorCode.ENGINE_UNAVAILABLE,
                    "model identifier lookup failed (no GMS?)", e);
        } catch (RuntimeException e) {
            throw new HandwritingException(HandwritingException.ErrorCode.ENGINE_UNAVAILABLE,
                    "model identifier lookup failed", e);
        }
        if (identifier == null) {
            throw new HandwritingException(HandwritingException.ErrorCode.INVALID_INPUT,
                    "unsupported language tag: " + languageTag);
        }
        return DigitalInkRecognitionModel.builder(identifier).build();
    }

    private DigitalInkRecognizer recognizerFor(DigitalInkRecognitionModel model)
            throws HandwritingException {
        synchronized (lock) {
            DigitalInkRecognizer r = recognizers.get(model.getModelIdentifier().getLanguageTag());
            if (r == null) {
                try {
                    r = DigitalInkRecognition.getClient(
                            DigitalInkRecognizerOptions.builder(model).build());
                } catch (RuntimeException e) {
                    AppLogger.w(TAG, "recognizer init failed (GMS missing?)", e);
                    throw new HandwritingException(HandwritingException.ErrorCode.ENGINE_UNAVAILABLE,
                            "digital ink unavailable on this device", e);
                }
                recognizers.put(model.getModelIdentifier().getLanguageTag(), r);
            }
            return r;
        }
    }

    private static HandwritingException mapFailure(Throwable t) {
        if (t instanceof MlKitException) {
            MlKitException mke = (MlKitException) t;
            switch (mke.getErrorCode()) {
                case MlKitException.UNAVAILABLE:
                    // Thin SDK: model delivery impossible without Play Services.
                    return new HandwritingException(HandwritingException.ErrorCode.ENGINE_UNAVAILABLE,
                            "handwriting engine unavailable (no GMS)", mke);
                case MlKitException.INVALID_ARGUMENT:
                    return new HandwritingException(HandwritingException.ErrorCode.INVALID_INPUT,
                            "invalid ink input", mke);
                default:
                    return new HandwritingException(HandwritingException.ErrorCode.RECOGNITION_FAILED,
                            "ML Kit error " + mke.getErrorCode(), mke);
            }
        }
        return new HandwritingException(HandwritingException.ErrorCode.RECOGNITION_FAILED,
                "recognition failed", t);
    }
}
