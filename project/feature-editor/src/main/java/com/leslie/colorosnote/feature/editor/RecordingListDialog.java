package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.feature.audio.AudioPlayerController;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Recording manager dialog (spec 20): lists every recording session saved in
 * the open note (duration / start time / segment count) with per-row play and
 * delete. Scanning runs on the IO executor; deletion removes the segment files
 * only — document persistence never references audio files, so notes are
 * unaffected. Delete is a two-tap confirm on the same button (no extra dialog).
 */
public final class RecordingListDialog {

    /** Caps the scroll area so long lists don't stretch the card. */
    private static final int MAX_LIST_HEIGHT_DP = 380;
    private static final long CONFIRM_RESET_MS = 3000L;

    private RecordingListDialog() {
    }

    public static AlertDialog show(Context context, File audioDir) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_recordings, null);
        TextView empty = view.findViewById(R.id.txt_recording_list_empty);
        ScrollView scroll = view.findViewById(R.id.recording_list_scroll);
        LinearLayout container = view.findViewById(R.id.recording_list_container);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        AudioPlayerController player = new AudioPlayerController(context);
        dialog.setOnDismissListener(d -> player.release());
        // Tracks the row currently playing so only one session plays at a time.
        final TextView[] playingButton = {null};
        player.setListener(new AudioPlayerController.Listener() {
            @Override
            public void onPosition(long positionMs, long durationMs) {
            }

            @Override
            public void onEnded() {
                if (playingButton[0] != null) {
                    playingButton[0].setText(R.string.recording_play);
                    playingButton[0] = null;
                }
            }

            @Override
            public void onError(String message) {
                onEnded();
                Toast.makeText(context, context.getString(
                        R.string.recording_playback_failed, message), Toast.LENGTH_SHORT).show();
            }
        });

        AppExecutors.get().io().execute(() -> {
            List<RecordingLibrary.Recording> recordings =
                    RecordingLibrary.scan(audioDir, new MetadataDurationProbe());
            AppExecutors.get().mainThread().post(() -> {
                if (dialog.isShowing()) {
                    populate(context, recordings, container, empty, scroll, player, playingButton);
                }
            });
        });

        dialog.show();
        return dialog;
    }

    private static void populate(Context context, List<RecordingLibrary.Recording> recordings,
                                 LinearLayout container, TextView empty, ScrollView scroll,
                                 AudioPlayerController player, TextView[] playingButton) {
        container.removeAllViews();
        if (recordings.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            scroll.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        SimpleDateFormat dayFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(context);
        for (RecordingLibrary.Recording recording : recordings) {
            View row = inflater.inflate(R.layout.item_recording, container, false);
            TextView title = row.findViewById(R.id.txt_recording_title);
            TextView meta = row.findViewById(R.id.txt_recording_meta);
            TextView btnPlay = row.findViewById(R.id.btn_recording_play);
            TextView btnDelete = row.findViewById(R.id.btn_recording_delete);

            title.setText(context.getString(R.string.recording_item_title,
                    dayFormat.format(recording.createdAtMs)));
            meta.setText(context.getString(R.string.recording_item_meta,
                    RecordingBarState.formatElapsed(recording.durationMs),
                    recording.segmentCount()));

            btnPlay.setOnClickListener(v -> {
                if (playingButton[0] == btnPlay && player.isPlaying()) {
                    player.pause();
                    btnPlay.setText(R.string.recording_play);
                    return;
                }
                if (playingButton[0] != null) {
                    playingButton[0].setText(R.string.recording_play);
                }
                player.pause();
                player.loadSegments(recording.segments);
                player.play();
                playingButton[0] = btnPlay;
                btnPlay.setText(R.string.recording_pause);
            });

            // Two-tap confirm: first tap turns the button into 确认删除.
            final Runnable[] resetDelete = {null};
            btnDelete.setOnClickListener(v -> {
                if (!btnDelete.isSelected()) {
                    btnDelete.setSelected(true);
                    btnDelete.setText(R.string.recording_delete_confirm);
                    resetDelete[0] = () -> {
                        btnDelete.setSelected(false);
                        btnDelete.setText(R.string.recording_delete);
                    };
                    btnDelete.postDelayed(resetDelete[0], CONFIRM_RESET_MS);
                    return;
                }
                if (resetDelete[0] != null) {
                    btnDelete.removeCallbacks(resetDelete[0]);
                }
                if (playingButton[0] == btnPlay) {
                    player.pause();
                    playingButton[0] = null;
                }
                if (RecordingLibrary.delete(recording)) {
                    container.removeView(row);
                    Toast.makeText(context, R.string.recording_deleted, Toast.LENGTH_SHORT).show();
                    if (container.getChildCount() == 0) {
                        empty.setVisibility(View.VISIBLE);
                        scroll.setVisibility(View.GONE);
                    }
                }
            });

            container.addView(row);
        }
        // Cap the list height once the rows exceed it.
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int max = BrushPropertiesPopup.dp(context, MAX_LIST_HEIGHT_DP);
                        if (container.getHeight() > max) {
                            scroll.getLayoutParams().height = max;
                            scroll.requestLayout();
                        }
                    }
                });
    }

    /** Android duration probe backed by MediaMetadataRetriever (IO thread). */
    private static final class MetadataDurationProbe implements RecordingLibrary.DurationProbe {
        @Override
        public long durationMs(File audioFile) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(audioFile.getAbsolutePath());
                String value = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                return value == null ? 0L : Long.parseLong(value);
            } catch (RuntimeException e) {
                return 0L;
            } finally {
                try {
                    mmr.release();
                } catch (IOException ignored) {
                    // Release failure is harmless for a duration probe.
                }
            }
        }
    }
}
