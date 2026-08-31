package com.leslie.colorosnote.feature.editor;

import android.content.Context;

import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.model.StrokePoint;
import com.leslie.colorosnote.feature.audio.AudioRecorderController;

import java.io.File;
import java.util.List;

/**
 * Bridges the editor and feature-audio (spec 20): toggles recording (the host
 * fragment owns the runtime-permission flow), user-facing pause/resume for the
 * floating control bar, exposes the recording time base and stamps finished
 * strokes with audio anchors
 * ({@code strokeStartAudioTimeMs}/{@code strokeEndAudioTimeMs}).
 *
 * <p>Each recording session gets a fresh {@link AudioRecorderController} with a
 * unique base name ({@code <base>_<timestamp>}), so sessions never overwrite
 * each other's rolling segments and {@link RecordingLibrary} can group them.
 *
 * <p>All methods run on the main thread.
 */
public final class RecordingController implements AudioRecorderController.Listener {

    public interface Ui {
        /** Host should launch its RECORD_AUDIO permission request. */
        void requestMicPermission();

        void onRecordingStateChanged(boolean recording);

        /** Recorder paused/resumed (user toggle, lifecycle, or focus loss). */
        void onRecordingPauseChanged(boolean paused);

        /**
         * A user-initiated stop completed: the session's segments are on disk.
         * Not fired for lifecycle teardown ({@link #release()}).
         */
        void onRecordingFinished(long durationMs, List<File> segments);

        void onRecordingElapsed(long elapsedMs);

        void onRecordingError(String message);
    }

    private static final String TAG = "RecordingController";

    private final Context context;
    private final File outputDir;
    private final String baseName;
    private final Ui ui;
    private AudioRecorderController recorder;
    private boolean pausedByLifecycle;

    public RecordingController(Context context, File outputDir, String baseName, Ui ui) {
        this.context = context;
        this.outputDir = outputDir;
        this.baseName = baseName;
        this.ui = ui;
    }

    public boolean isRecording() {
        return recorder != null
                && (recorder.getState() == AudioRecorderController.State.RECORDING
                || recorder.getState() == AudioRecorderController.State.PAUSED);
    }

    public boolean isPaused() {
        return recorder != null
                && recorder.getState() == AudioRecorderController.State.PAUSED;
    }

    /** Elapsed recording time of the active session, 0 when idle. */
    public long elapsedMs() {
        return recorder == null ? 0L : recorder.elapsedTimeMs();
    }

    /** Toggle button entry point. */
    public void toggle() {
        if (isRecording()) {
            stopRecording();
            return;
        }
        if (!hasMicPermission()) {
            ui.requestMicPermission();
            return;
        }
        startInternal();
    }

    private boolean hasMicPermission() {
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** User-facing pause/resume from the control bar. */
    public void togglePause() {
        if (recorder == null) {
            return;
        }
        if (recorder.getState() == AudioRecorderController.State.RECORDING) {
            recorder.pause();
        } else if (recorder.getState() == AudioRecorderController.State.PAUSED) {
            pausedByLifecycle = false; // user took over; no auto-resume pending
            recorder.resume();
        }
    }

    /** User-initiated stop: saves segments and reports the finished session. */
    public void stopRecording() {
        if (!isRecording()) {
            return;
        }
        pausedByLifecycle = false;
        long durationMs = recorder.elapsedTimeMs();
        List<File> segments = recorder.getSegments();
        recorder.stop();
        ui.onRecordingStateChanged(false);
        if (!segments.isEmpty()) {
            ui.onRecordingFinished(durationMs, segments);
        }
    }

    /** Called by the host when the permission request resolves. */
    public void onPermissionResult(boolean granted) {
        if (granted) {
            startInternal();
        } else {
            ui.onRecordingError("mic permission denied");
        }
    }

    private void startInternal() {
        // Fresh controller per session: the unique base name keeps each
        // session's rolling segments separate (RecordingLibrary groups by it).
        recorder = new AudioRecorderController(context, outputDir,
                baseName + "_" + System.currentTimeMillis());
        recorder.setListener(this);
        pausedByLifecycle = false;
        recorder.start();
        if (recorder.getState() == AudioRecorderController.State.RECORDING) {
            ui.onRecordingStateChanged(true);
        }
    }

    /** Recording time base for stroke anchoring (ms since recording start). */
    public long timeBaseMs() {
        return recorder == null ? 0L : recorder.recordingTimeBaseMs();
    }

    /**
     * Stamps audio anchors on a just-finished stroke when recording. Stroke
     * point timestamps are MotionEvent event-times; only their delta is used.
     */
    public void anchorStrokeIfRecording(Stroke stroke) {
        if (!isRecording() || stroke.points.isEmpty()) {
            return;
        }
        long end = timeBaseMs();
        StrokePoint first = stroke.points.get(0);
        StrokePoint last = stroke.points.get(stroke.points.size() - 1);
        long duration = Math.max(0L, last.timestampMs - first.timestampMs);
        stroke.strokeEndAudioTimeMs = end;
        stroke.strokeStartAudioTimeMs = Math.max(0L, end - duration);
    }

    /** Pause while the app goes to the background; resume on return. */
    public void onHostPause() {
        if (recorder != null
                && recorder.getState() == AudioRecorderController.State.RECORDING) {
            recorder.pause();
            pausedByLifecycle = true;
        }
    }

    public void onHostResume() {
        if (pausedByLifecycle && recorder != null
                && recorder.getState() == AudioRecorderController.State.PAUSED) {
            recorder.resume();
        }
        pausedByLifecycle = false;
    }

    /** Silent teardown (view destroyed): stops without a finished callback. */
    public void release() {
        if (isRecording()) {
            recorder.stop();
        }
    }

    // ------------------------------------------------------------------
    // AudioRecorderController.Listener
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged(AudioRecorderController.State state) {
        switch (state) {
            case PAUSED:
                ui.onRecordingPauseChanged(true);
                break;
            case RECORDING:
                ui.onRecordingPauseChanged(false);
                break;
            case STOPPED:
            case ERROR:
            case IDLE:
            default:
                ui.onRecordingStateChanged(false);
                break;
        }
    }

    @Override
    public void onElapsed(long elapsedMs) {
        ui.onRecordingElapsed(elapsedMs);
    }

    @Override
    public void onSegmentStarted(File file, int index) {
        AppLogger.d(TAG, "segment " + index + " started");
    }

    @Override
    public void onError(String message) {
        ui.onRecordingError(message);
    }
}
