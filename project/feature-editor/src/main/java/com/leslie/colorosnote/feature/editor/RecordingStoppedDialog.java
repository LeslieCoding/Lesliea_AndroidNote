package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.feature.audio.AudioPlayerController;

import java.io.File;
import java.util.List;

/**
 * Card dialog shown after the user stops a recording (spec 20): reports the
 * duration, states that the audio is saved inside the note, and offers
 * 播放 (built-in player), 转文字 (via {@link SpeechTranscriptionGateway}) and
 * 完成. The player is released when the dialog dismisses.
 */
public final class RecordingStoppedDialog {

    private RecordingStoppedDialog() {
    }

    public static AlertDialog show(Context context, long durationMs, List<File> segments,
                                   SpeechTranscriptionGateway gateway,
                                   RecognitionResultDialog.Listener transcriptListener) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_recording_stopped, null);
        TextView message = view.findViewById(R.id.txt_recording_saved_message);
        TextView btnPlay = view.findViewById(R.id.btn_recording_play);
        TextView btnTranscribe = view.findViewById(R.id.btn_recording_transcribe);
        TextView btnDone = view.findViewById(R.id.btn_recording_done);

        message.setText(context.getString(R.string.recording_saved_message,
                RecordingBarState.formatElapsed(durationMs)));

        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        AudioPlayerController player = new AudioPlayerController(context);
        player.loadSegments(segments);
        player.setListener(new AudioPlayerController.Listener() {
            @Override
            public void onPosition(long positionMs, long durationMs) {
            }

            @Override
            public void onEnded() {
                btnPlay.setText(R.string.recording_play);
            }

            @Override
            public void onError(String message) {
                btnPlay.setText(R.string.recording_play);
                toast(context, context.getString(R.string.recording_playback_failed, message));
            }
        });
        dialog.setOnDismissListener(d -> player.release());

        btnPlay.setOnClickListener(v -> {
            if (player.isPlaying()) {
                player.pause();
                btnPlay.setText(R.string.recording_play);
            } else {
                player.play();
                btnPlay.setText(R.string.recording_pause);
            }
        });

        final Runnable startTranscription = () -> {
            if (segments.isEmpty()) {
                return;
            }
            btnTranscribe.setEnabled(false);
            btnTranscribe.setText(R.string.recording_transcribing);
            // One file per call; transcribe the first segment of the session.
            gateway.transcribe(segments.get(0), new SpeechTranscriptionGateway.Callback() {
                @Override
                public void onTranscript(String text) {
                    btnTranscribe.setEnabled(true);
                    btnTranscribe.setText(R.string.recording_transcribe);
                    if (text == null || text.trim().isEmpty()) {
                        toast(context, context.getString(R.string.recording_transcript_empty));
                        return;
                    }
                    RecognitionResultDialog.show(context,
                            R.string.recording_transcript_result, text,
                            transcriptListener, view);
                }

                @Override
                public void onError(String errorMessage) {
                    btnTranscribe.setEnabled(true);
                    btnTranscribe.setText(R.string.recording_transcribe);
                    toast(context, context.getString(
                            R.string.recording_transcript_failed, errorMessage));
                }
            });
        };

        btnTranscribe.setOnClickListener(v -> {
            if (!gateway.isAvailable()) {
                // Model missing: offer the download card first, then transcribe.
                if (gateway instanceof VoskSpeechTranscriptionGateway) {
                    SpeechModelDownloadDialog.show(context,
                            (VoskSpeechTranscriptionGateway) gateway,
                            new SpeechModelDownloadDialog.Listener() {
                                @Override
                                public void onModelReady() {
                                    startTranscription.run();
                                }

                                @Override
                                public void onDismissed() {
                                }
                            });
                } else {
                    toast(context, context.getString(R.string.recording_transcribe_unavailable));
                }
                return;
            }
            startTranscription.run();
        });

        btnDone.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        return dialog;
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
