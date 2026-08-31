package com.leslie.colorosnote.feature.editor;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.feature.handwriting.HandwritingException;
import com.leslie.colorosnote.feature.handwriting.MlKitHandwritingRecognizer;
import com.leslie.colorosnote.feature.handwriting.StrokeData;

import java.util.List;

/**
 * 「手写转文字」流程控制器：本页笔迹 →（必要时下载中文手写模型）→ 分行识别
 * → 结果对话框（插入文本框 / 复制 / 取消）。
 *
 * <p>模型检查、下载与识别全部跑在 {@link AppExecutors#background()} 低优先级
 * 单线程上（模型操作是阻塞式 API，放主线程会 ANR），UI 操作 post 回主线程。
 * 识别编排（分行 / pre-context / 汇总）在纯 JVM 的
 * {@link HandwritingToTextPlanner} 中，可单测。
 *
 * <p>无 GMS 设备：ML Kit bundled 版理论上不依赖 GMS，但引擎初始化/模型下载
 * 仍可能失败，失败时以 {@link HandwritingException.ErrorCode} 分类给出明确
 * 中文提示，绝不崩溃或编造结果。
 */
public final class HandwritingToTextController {

    private static final String TAG = "HandwritingToText";

    private final EditorFragment fragment;
    @Nullable
    private AlertDialog activeDialog;
    /** 防止流程进行中被重复触发。 */
    private boolean running;

    public HandwritingToTextController(EditorFragment fragment) {
        this.fragment = fragment;
    }

    public void start() {
        if (running) {
            return;
        }
        Page page = fragment.getCurrentPage();
        List<StrokeData> strokes = HandwritingToTextPlanner.adaptStrokes(
                page == null ? null : page.strokes);
        if (strokes.isEmpty()) {
            CreamToast.show(fragment.getView(), R.string.recog_no_ink);
            return;
        }
        running = true;
        AppExecutors.get().background().execute(() -> ensureModelAndRecognize(strokes));
    }

    // ------------------------------------------------------------------
    // Background pipeline
    // ------------------------------------------------------------------

    private void ensureModelAndRecognize(List<StrokeData> strokes) {
        MlKitHandwritingRecognizer recognizer = new MlKitHandwritingRecognizer();
        try {
            if (!recognizer.isModelDownloaded()) {
                if (!downloadModel(recognizer)) {
                    return; // failure already surfaced to the user
                }
            }
            runRecognize(recognizer, strokes);
        } catch (HandwritingException e) {
            AppLogger.w(TAG, "model check failed: " + e.code(), e);
            showError(e.code(), e.code() != HandwritingException.ErrorCode.ENGINE_UNAVAILABLE);
        } finally {
            recognizer.close();
            running = false;
        }
    }

    /** @return true when the model is ready (already present or downloaded). */
    private boolean downloadModel(MlKitHandwritingRecognizer recognizer)
            throws HandwritingException {
        runOnUi(() -> {
            Context context = fragment.getContext();
            if (context != null) {
                activeDialog = CreamProgressDialog.show(context,
                        R.string.recog_model_download_title, R.string.recog_model_downloading);
            }
        });
        try {
            recognizer.downloadModel();
            dismissActive();
            return true;
        } catch (HandwritingException e) {
            AppLogger.w(TAG, "model download failed: " + e.code(), e);
            dismissActive();
            // 网络问题占多数，提供重试；引擎本身不可用（无 GMS）则只说明原因。
            showError(e.code(), e.code() != HandwritingException.ErrorCode.ENGINE_UNAVAILABLE);
            return false;
        }
    }

    private void runRecognize(MlKitHandwritingRecognizer recognizer, List<StrokeData> strokes) {
        runOnUi(() -> {
            Context context = fragment.getContext();
            if (context != null) {
                activeDialog = CreamProgressDialog.show(context,
                        R.string.aux_handwriting, R.string.recog_recognizing);
            }
        });
        try {
            String text = HandwritingToTextPlanner.recognizePage(recognizer, strokes);
            dismissActive();
            String result = text;
            runOnUi(() -> {
                if (result.trim().isEmpty()) {
                    CreamToast.show(fragment.getView(), R.string.recog_empty_result);
                    return;
                }
                Context context = fragment.getContext();
                if (context == null) {
                    return;
                }
                RecognitionResultDialog.show(context,
                        R.string.recog_result_title_handwriting, result,
                        t -> RecognizedTextInserter.insertBelowContent(fragment, t),
                        fragment.getView());
            });
        } catch (HandwritingException e) {
            AppLogger.w(TAG, "recognition failed: " + e.code(), e);
            dismissActive();
            showError(e.code(), e.code() == HandwritingException.ErrorCode.MODEL_NOT_DOWNLOADED);
        }
    }

    // ------------------------------------------------------------------
    // UI helpers (all hop onto the main thread)
    // ------------------------------------------------------------------

    private void showError(HandwritingException.ErrorCode code, boolean offerRetry) {
        int messageRes;
        switch (code) {
            case ENGINE_UNAVAILABLE:
                messageRes = R.string.recog_engine_unavailable;
                break;
            case MODEL_NOT_DOWNLOADED:
                messageRes = R.string.recog_model_download_failed;
                break;
            default:
                messageRes = R.string.recog_failed;
                break;
        }
        runOnUi(() -> {
            Context context = fragment.getContext();
            if (context != null) {
                CreamNoticeDialog.show(context, R.string.aux_handwriting, messageRes,
                        offerRetry ? this::start : null);
            }
        });
    }

    private void dismissActive() {
        runOnUi(() -> {
            if (activeDialog != null) {
                activeDialog.dismiss();
                activeDialog = null;
            }
        });
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
