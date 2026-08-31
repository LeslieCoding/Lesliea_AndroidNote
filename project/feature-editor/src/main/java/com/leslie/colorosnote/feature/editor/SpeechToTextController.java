package com.leslie.colorosnote.feature.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.feature.audio.VoskLiveTranscriber;

/**
 * 「+ → 语音转文字」entry point (spec 20.4): a milky-white card dialog that
 * streams the mic through the Vosk offline engine and shows the live
 * transcript. States: 未开始 / 录音中 / 已暂停 / 识别中 / 识别完成; on stop the
 * full text is offered with 插入为文本框 / 复制 / 丢弃.
 *
 * <p>First use downloads the 42MB offline model via
 * {@link SpeechModelDownloadDialog} before the card opens. Mic permission goes
 * through the host fragment's dedicated launcher
 * ({@link EditorFragment#requestSpeechMicPermission}); capture and recognition
 * stay on background threads ({@link VoskLiveTranscriber} posts its callbacks
 * to the main thread).
 */
final class SpeechToTextController implements VoskLiveTranscriber.Listener {

    /** Receives the final transcript for undoable insertion (host: ObjectToolController). */
    interface Host {
        void insertTranscriptTextBox(String text);
    }

    private final EditorFragment fragment;
    private final Host host;

    private AlertDialog dialog;
    private VoskLiveTranscriber transcriber;
    private VoskLiveTranscriber.State state = VoskLiveTranscriber.State.IDLE;
    private boolean resultShown;

    private TextView txtStatus;
    private TextView txtText;
    private TextView btnPause;
    private TextView btnPrimary;
    private View resultActions;
    private View controls;

    SpeechToTextController(EditorFragment fragment, Host host) {
        this.fragment = fragment;
        this.host = host;
    }

    void show() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        VoskSpeechTranscriptionGateway gateway = gateway(context);
        if (!gateway.isAvailable()) {
            // Model missing: download first, open the card when ready.
            SpeechModelDownloadDialog.show(context, gateway,
                    new SpeechModelDownloadDialog.Listener() {
                        @Override
                        public void onModelReady() {
                            show();
                        }

                        @Override
                        public void onDismissed() {
                        }
                    });
            return;
        }
        showMainDialog(context, gateway);
    }

    private void showMainDialog(Context context, VoskSpeechTranscriptionGateway gateway) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_speech_to_text, null);
        txtStatus = view.findViewById(R.id.txt_stt_status);
        txtText = view.findViewById(R.id.txt_stt_text);
        btnPause = view.findViewById(R.id.btn_stt_pause);
        btnPrimary = view.findViewById(R.id.btn_stt_primary);
        TextView btnCancel = view.findViewById(R.id.btn_stt_cancel);
        TextView btnInsert = view.findViewById(R.id.btn_stt_insert);
        TextView btnCopy = view.findViewById(R.id.btn_stt_copy);
        TextView btnDiscard = view.findViewById(R.id.btn_stt_discard);
        resultActions = view.findViewById(R.id.stt_result_actions);
        controls = view.findViewById(R.id.stt_controls);

        dialog = new AlertDialog.Builder(context).setView(view).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        transcriber = gateway.newLiveTranscriber(this);
        dialog.setOnDismissListener(d -> releaseTranscriber());

        btnPrimary.setOnClickListener(v -> {
            if (resultShown) {
                return;
            }
            if (state == VoskLiveTranscriber.State.IDLE
                    || state == VoskLiveTranscriber.State.FAILED) {
                startWithPermission(context);
            } else {
                transcriber.stopAndFinish();
            }
        });
        btnPause.setOnClickListener(v -> {
            if (state == VoskLiveTranscriber.State.RECORDING) {
                transcriber.setPaused(true);
            } else if (state == VoskLiveTranscriber.State.PAUSED) {
                transcriber.setPaused(false);
            }
        });
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnInsert.setOnClickListener(v -> {
            String text = txtText.getText().toString().trim();
            if (text.isEmpty()) {
                toast(context, R.string.stt_no_result);
                return;
            }
            host.insertTranscriptTextBox(text);
            toast(context, R.string.stt_inserted);
            dialog.dismiss();
        });
        btnCopy.setOnClickListener(v -> {
            String text = txtText.getText().toString();
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("语音转文字", text));
            toast(context, R.string.stt_copied);
        });
        btnDiscard.setOnClickListener(v -> dialog.dismiss());

        applyState();
        dialog.show();
    }

    private void startWithPermission(Context context) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            transcriber.start();
            return;
        }
        fragment.requestSpeechMicPermission(granted -> {
            if (granted) {
                VoskLiveTranscriber t = transcriber;
                if (t != null && dialog != null && dialog.isShowing()) {
                    t.start();
                }
            } else {
                Context c = fragment.getContext();
                if (c != null) {
                    toast(c, R.string.stt_permission_denied);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // VoskLiveTranscriber.Listener (main thread)
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged(VoskLiveTranscriber.State newState) {
        state = newState;
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        if (newState == VoskLiveTranscriber.State.IDLE && resultShown) {
            applyState();
            return;
        }
        applyState();
    }

    @Override
    public void onPartial(String text) {
        refreshText();
    }

    @Override
    public void onCommittedText(String fullText) {
        if (state == VoskLiveTranscriber.State.FINISHING
                || state == VoskLiveTranscriber.State.IDLE) {
            // Stop path: show the result actions.
            resultShown = true;
            txtText.setText(fullText);
            applyState();
        } else {
            refreshText();
        }
    }

    @Override
    public void onError(String message) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        applyState();
    }

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    private void refreshText() {
        VoskLiveTranscriber t = transcriber;
        if (t != null && txtText != null) {
            txtText.setText(t.currentText());
        }
    }

    private void applyState() {
        if (txtStatus == null) {
            return;
        }
        if (resultShown) {
            txtStatus.setText(R.string.stt_status_done);
            btnPrimary.setVisibility(View.GONE);
            btnPause.setVisibility(View.GONE);
            resultActions.setVisibility(View.VISIBLE);
            return;
        }
        switch (state) {
            case LOADING:
                txtStatus.setText(R.string.stt_status_loading);
                btnPrimary.setEnabled(false);
                btnPause.setVisibility(View.GONE);
                break;
            case RECORDING:
                txtStatus.setText(R.string.stt_status_recording);
                btnPrimary.setEnabled(true);
                btnPrimary.setText(R.string.stt_stop);
                btnPause.setVisibility(View.VISIBLE);
                btnPause.setText(R.string.stt_pause);
                break;
            case PAUSED:
                txtStatus.setText(R.string.stt_status_paused);
                btnPrimary.setEnabled(true);
                btnPrimary.setText(R.string.stt_stop);
                btnPause.setVisibility(View.VISIBLE);
                btnPause.setText(R.string.stt_resume);
                break;
            case FINISHING:
                txtStatus.setText(R.string.stt_status_recognizing);
                btnPrimary.setEnabled(false);
                btnPause.setVisibility(View.GONE);
                break;
            case FAILED:
                txtStatus.setText(R.string.stt_status_idle);
                btnPrimary.setEnabled(true);
                btnPrimary.setText(R.string.stt_start);
                btnPause.setVisibility(View.GONE);
                break;
            case IDLE:
            default:
                txtStatus.setText(R.string.stt_status_idle);
                btnPrimary.setEnabled(true);
                btnPrimary.setText(R.string.stt_start);
                btnPause.setVisibility(View.GONE);
                break;
        }
    }

    /** Fragment teardown: stop capture and close the card. */
    void dispose() {
        releaseTranscriber();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void releaseTranscriber() {
        VoskLiveTranscriber t = transcriber;
        transcriber = null;
        if (t != null) {
            t.release();
        }
    }

    private VoskSpeechTranscriptionGateway gateway(Context context) {
        SpeechTranscriptionGateway g = EditorServices.get(context).speechTranscription();
        return (VoskSpeechTranscriptionGateway) g;
    }

    private void toast(Context context, int res) {
        Toast.makeText(context.getApplicationContext(), res, Toast.LENGTH_SHORT).show();
    }
}
