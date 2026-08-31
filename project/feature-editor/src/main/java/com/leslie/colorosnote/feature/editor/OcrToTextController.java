package com.leslie.colorosnote.feature.editor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.feature.ocr.MlKitOcrEngine;
import com.leslie.colorosnote.feature.ocr.OcrException;
import com.leslie.colorosnote.feature.ocr.OcrOptions;
import com.leslie.colorosnote.feature.ocr.OcrResult;

/**
 * 「OCR（图片文字识别）」流程控制器：系统相册选图 → ML Kit 中文文本识别 →
 * 结果对话框（插入文本框 / 复制 / 取消）。
 *
 * <p>选图复用宿主的 {@link ObjectToolController.ImageInsertHost}（ActivityResult
 * 相册选择器由宿主 Activity 持有，编辑器不直接注册 launcher）。引擎可用性
 * 检查与识别跑在 {@link AppExecutors#background()} 上，不卡 UI 线程。
 *
 * <p>无 GMS 设备：引擎初始化失败时给出明确中文提示（{@link
 * OcrException.ErrorCode#ENGINE_UNAVAILABLE}），不崩溃。
 */
public final class OcrToTextController {

    private static final String TAG = "OcrToText";

    private final EditorFragment fragment;
    @Nullable
    private AlertDialog activeDialog;

    public OcrToTextController(EditorFragment fragment) {
        this.fragment = fragment;
    }

    public void start() {
        Activity activity = fragment.getActivity();
        if (!(activity instanceof ObjectToolController.ImageInsertHost)) {
            toast(R.string.obj_image_no_host);
            return;
        }
        ObjectToolController.ImageInsertHost host =
                (ObjectToolController.ImageInsertHost) activity;
        // Availability probe off the main thread: engine init loads native ML
        // Kit code and fails (typed) on devices without the required services.
        AppExecutors.get().background().execute(() -> {
            MlKitOcrEngine engine = new MlKitOcrEngine();
            if (!engine.isAvailable()) {
                engine.close();
                showError(OcrException.ErrorCode.ENGINE_UNAVAILABLE);
                return;
            }
            runOnUi(() -> pickImage(host, engine));
        });
    }

    // ------------------------------------------------------------------
    // Gallery pick (main thread) -> recognition (background)
    // ------------------------------------------------------------------

    private void pickImage(ObjectToolController.ImageInsertHost host, MlKitOcrEngine engine) {
        host.requestImagePick(new ObjectToolController.ImagePickCallback() {
            @Override
            public void onImagePicked(String resourceId, int widthPx, int heightPx) {
                Bitmap bitmap = EditorBitmapRegistry.get(resourceId);
                if (bitmap == null) {
                    closeEngine(engine);
                    toast(R.string.obj_image_decode_failed);
                    return;
                }
                runOnUi(() -> {
                    Context context = fragment.getContext();
                    if (context != null) {
                        activeDialog = CreamProgressDialog.show(context,
                                R.string.aux_ocr, R.string.recog_recognizing);
                    }
                });
                AppExecutors.get().background().execute(() -> recognize(engine, bitmap, resourceId));
            }

            @Override
            public void onImagePickCancelled() {
                closeEngine(engine);
            }
        });
    }

    private void recognize(MlKitOcrEngine engine, Bitmap bitmap, String resourceId) {
        try {
            OcrResult result = engine.recognize(bitmap, OcrOptions.defaults());
            String text = result.allText() == null ? "" : result.allText().trim();
            dismissActive();
            runOnUi(() -> {
                if (text.isEmpty()) {
                    CreamToast.show(fragment.getView(), R.string.recog_empty_result);
                    return;
                }
                Context context = fragment.getContext();
                if (context == null) {
                    return;
                }
                RecognitionResultDialog.show(context,
                        R.string.recog_result_title_ocr, text,
                        t -> RecognizedTextInserter.insertBelowContent(fragment, t),
                        fragment.getView());
            });
        } catch (OcrException e) {
            AppLogger.w(TAG, "OCR failed: " + e.code(), e);
            dismissActive();
            showError(e.code());
        } finally {
            // The bitmap is only an OCR source (not inserted as an image
            // object): release the in-memory registry entry.
            EditorBitmapRegistry.remove(resourceId);
            closeEngine(engine);
        }
    }

    // ------------------------------------------------------------------
    // UI helpers (all hop onto the main thread)
    // ------------------------------------------------------------------

    private void showError(OcrException.ErrorCode code) {
        int messageRes;
        switch (code) {
            case ENGINE_UNAVAILABLE:
                messageRes = R.string.recog_engine_unavailable;
                break;
            case MODEL_NOT_READY:
                messageRes = R.string.recog_ocr_model_not_ready;
                break;
            case INVALID_INPUT:
                messageRes = R.string.obj_image_decode_failed;
                break;
            default:
                messageRes = R.string.recog_failed;
                break;
        }
        runOnUi(() -> {
            Context context = fragment.getContext();
            if (context != null) {
                CreamNoticeDialog.show(context, R.string.aux_ocr, messageRes);
            }
        });
    }

    private void closeEngine(MlKitOcrEngine engine) {
        AppExecutors.get().background().execute(engine::close);
    }

    private void dismissActive() {
        runOnUi(() -> {
            if (activeDialog != null) {
                activeDialog.dismiss();
                activeDialog = null;
            }
        });
    }

    private void toast(int textRes) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, textRes, Toast.LENGTH_SHORT).show();
        }
    }

    private void runOnUi(Runnable action) {
        if (!fragment.isAdded()) {
            return;
        }
        AppExecutors.get().mainThread().post(() -> {
            if (fragment.isAdded()) {
                action.run();
            }
        });
    }
}
