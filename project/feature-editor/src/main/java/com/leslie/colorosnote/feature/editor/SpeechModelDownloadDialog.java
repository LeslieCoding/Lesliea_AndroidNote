package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.feature.audio.VoskModelManager;

/**
 * Milky-white model download progress card (spec 21.1): shown the first time a
 * speech feature is used without the 42MB offline Chinese model installed.
 * Download runs on the gateway's worker executor; progress lands on the main
 * thread. Cancel just dismisses the card (the download finishes in the
 * background and is reused next time).
 */
public final class SpeechModelDownloadDialog {

    public interface Listener {
        void onModelReady();

        /** Dialog dismissed (cancel or failure). */
        void onDismissed();
    }

    private SpeechModelDownloadDialog() {
    }

    public static AlertDialog show(Context context, VoskSpeechTranscriptionGateway gateway,
                                   Listener listener) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_speech_model_download, null);
        ProgressBar bar = view.findViewById(R.id.stt_download_progress);
        TextView percent = view.findViewById(R.id.txt_stt_download_percent);
        TextView btnCancel = view.findViewById(R.id.btn_stt_download_cancel);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final boolean[] ready = {false};
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (!ready[0]) {
                listener.onDismissed();
            }
        });

        gateway.ensureModel(new VoskModelManager.ProgressCallback() {
            @Override
            public void onProgress(int p, String stage) {
                bar.setProgress(p);
                percent.setText(context.getString(R.string.stt_model_progress, p));
            }
        }, new VoskSpeechTranscriptionGateway.ModelCallback() {
            @Override
            public void onModelReady() {
                ready[0] = true;
                dialog.dismiss();
                listener.onModelReady();
            }

            @Override
            public void onModelError(String message) {
                Toast.makeText(context.getApplicationContext(),
                        context.getString(R.string.stt_model_failed, message),
                        Toast.LENGTH_LONG).show();
                dialog.dismiss();
            }
        });
        dialog.show();
        return dialog;
    }
}
