package com.leslie.colorosnote.feature.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.leslie.colorosnote.core.common.AppLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * ML Kit Text Recognition v2 with the Chinese script model (spec 14.1).
 *
 * <p>Blocking worker-thread wrapper around the Task-based API. Devices without
 * GMS (typical ColorOS) surface {@link OcrException.ErrorCode#ENGINE_UNAVAILABLE}
 * or {@link OcrException.ErrorCode#MODEL_NOT_READY} instead of crashing.
 */
public final class MlKitOcrEngine implements OcrEngine {

    private static final String TAG = "MlKitOcrEngine";

    private final Object lock = new Object();
    private TextRecognizer recognizer;
    private boolean unavailable;

    @Override
    @WorkerThread
    @NonNull
    public OcrResult recognize(@NonNull Bitmap bitmap, @NonNull OcrOptions options)
            throws OcrException {
        if (bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new OcrException(OcrException.ErrorCode.INVALID_INPUT, "empty or recycled bitmap");
        }
        TextRecognizer client = recognizer();
        Text text;
        try {
            text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.ErrorCode.RECOGNITION_FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            throw mapFailure(e.getCause());
        } catch (RuntimeException e) {
            // Thin SDK can throw directly when Play Services is missing.
            throw mapFailure(e);
        }
        return convert(text, bitmap.getWidth(), bitmap.getHeight());
    }

    @Override
    public boolean isAvailable() {
        try {
            recognizer();
            return true;
        } catch (OcrException e) {
            return false;
        }
    }

    @Override
    @WorkerThread
    public void close() {
        synchronized (lock) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    private TextRecognizer recognizer() throws OcrException {
        synchronized (lock) {
            if (unavailable) {
                throw new OcrException(OcrException.ErrorCode.ENGINE_UNAVAILABLE,
                        "ML Kit text recognizer previously failed to initialize");
            }
            if (recognizer == null) {
                try {
                    recognizer = TextRecognition.getClient(
                            new ChineseTextRecognizerOptions.Builder().build());
                } catch (RuntimeException e) {
                    unavailable = true;
                    AppLogger.w(TAG, "recognizer init failed (GMS missing?)", e);
                    throw new OcrException(OcrException.ErrorCode.ENGINE_UNAVAILABLE,
                            "ML Kit unavailable on this device", e);
                }
            }
            return recognizer;
        }
    }

    private static OcrException mapFailure(Throwable t) {
        if (t instanceof MlKitException) {
            MlKitException mke = (MlKitException) t;
            switch (mke.getErrorCode()) {
                case MlKitException.UNAVAILABLE:
                    // Thin SDK: model not delivered or Play Services missing.
                    return new OcrException(OcrException.ErrorCode.MODEL_NOT_READY,
                            "OCR model unavailable (no GMS or not downloaded)", mke);
                case MlKitException.INVALID_ARGUMENT:
                    return new OcrException(OcrException.ErrorCode.INVALID_INPUT,
                            "invalid input image", mke);
                default:
                    return new OcrException(OcrException.ErrorCode.RECOGNITION_FAILED,
                            "ML Kit error " + mke.getErrorCode(), mke);
            }
        }
        return new OcrException(OcrException.ErrorCode.RECOGNITION_FAILED,
                "recognition failed", t);
    }

    private static OcrResult convert(Text text, int width, int height) {
        List<OcrResult.Block> blocks = new ArrayList<>();
        for (Text.TextBlock tb : text.getTextBlocks()) {
            List<OcrResult.Line> lines = new ArrayList<>();
            for (Text.Line tl : tb.getLines()) {
                List<OcrResult.Element> elements = new ArrayList<>();
                for (Text.Element te : tl.getElements()) {
                    elements.add(new OcrResult.Element(
                            te.getText(), toBox(te.getBoundingBox()), te.getConfidence()));
                }
                lines.add(new OcrResult.Line(
                        tl.getText(), toBox(tl.getBoundingBox()), tl.getConfidence(), elements));
            }
            blocks.add(new OcrResult.Block(tb.getText(), toBox(tb.getBoundingBox()), lines));
        }
        return new OcrResult(blocks, width, height);
    }

    private static OcrBox toBox(Rect r) {
        return r == null ? null : new OcrBox(r.left, r.top, r.right, r.bottom);
    }
}
