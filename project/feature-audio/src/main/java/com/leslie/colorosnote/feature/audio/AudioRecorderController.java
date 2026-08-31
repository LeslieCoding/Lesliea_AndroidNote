package com.leslie.colorosnote.feature.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import com.leslie.colorosnote.core.common.AppLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaRecorder-based classroom recorder (spec 20.2): AAC/M4A 44.1kHz mono,
 * rolling segment files (crash loses at most the current segment), audio-focus
 * aware, pause/resume, elapsed-time callbacks.
 * <p>
 * Threading: all public methods must be called on the main thread; encoding is
 * handled by MediaRecorder internally off the UI thread.
 */
public final class AudioRecorderController {

    private static final String TAG = "AudioRecorder";

    public enum State { IDLE, RECORDING, PAUSED, STOPPED, ERROR }

    public interface Listener {
        void onStateChanged(State state);

        void onElapsed(long elapsedMs);

        void onSegmentStarted(File file, int index);

        void onError(String message);
    }

    private static final long MIN_FREE_BYTES = 50L * 1024 * 1024; // 50 MB guard
    private static final long TICK_MS = 500;

    private final Context context;
    private final File outputDir;
    private final String baseName;
    private final SegmentRollingPolicy rolling;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private State state = State.IDLE;
    private Listener listener;
    private final List<File> segments = new ArrayList<>();
    private long startedAtMs;
    private long accumulatedMs;
    private AudioFocusRequest focusRequest;

    public AudioRecorderController(Context context, File outputDir, String baseName) {
        this.context = context.getApplicationContext();
        this.outputDir = outputDir;
        this.baseName = baseName;
        this.rolling = new SegmentRollingPolicy(SegmentRollingPolicy.DEFAULT_SEGMENT_MS);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State getState() {
        return state;
    }

    public List<File> getSegments() {
        return new ArrayList<>(segments);
    }

    /** Elapsed recording time in ms (used by InkAudioTimeline anchors). */
    public long elapsedTimeMs() {
        if (state == State.RECORDING) {
            return accumulatedMs + (System.currentTimeMillis() - startedAtMs);
        }
        return accumulatedMs;
    }

    public boolean hasMicPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public synchronized void start() {
        if (state == State.RECORDING || state == State.PAUSED) return;
        if (!hasMicPermission()) {
            fail("microphone permission not granted");
            return;
        }
        if (freeBytes() < MIN_FREE_BYTES) {
            fail("insufficient storage");
            return;
        }
        if (!requestFocus()) {
            AppLogger.w(TAG, "audio focus denied, recording anyway");
        }
        accumulatedMs = 0;
        rolling.onTick(0);
        if (!startSegment()) return;
        startedAtMs = System.currentTimeMillis();
        setState(State.RECORDING);
        handler.post(ticker);
    }

    public synchronized void pause() {
        if (state != State.RECORDING) return;
        try {
            recorder.pause();
            accumulatedMs = elapsedTimeMs();
            setState(State.PAUSED);
        } catch (Exception e) {
            fail("pause failed: " + e.getMessage());
        }
    }

    public synchronized void resume() {
        if (state != State.PAUSED) return;
        try {
            recorder.resume();
            startedAtMs = System.currentTimeMillis();
            setState(State.RECORDING);
            handler.post(ticker);
        } catch (Exception e) {
            fail("resume failed: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (state != State.RECORDING && state != State.PAUSED) return;
        accumulatedMs = elapsedTimeMs();
        stopSegment();
        abandonFocus();
        setState(State.STOPPED);
        handler.removeCallbacks(ticker);
    }

    /** Current audio time base for stroke anchoring (spec 20.1). */
    public long recordingTimeBaseMs() {
        return elapsedTimeMs();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (state != State.RECORDING) return;
            long elapsed = elapsedTimeMs();
            if (rolling.onTick(elapsed)) {
                // roll to a fresh segment file
                stopSegment();
                if (!startSegment()) return;
            }
            if (listener != null) listener.onElapsed(elapsed);
            if (freeBytes() < MIN_FREE_BYTES) {
                fail("storage full");
                return;
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private boolean startSegment() {
        File f = new File(outputDir,
                SegmentRollingPolicy.segmentFileName(baseName, rolling.currentSegmentIndex()));
        //noinspection ResultOfMethodCallIgnored
        outputDir.mkdirs();
        recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(96_000);
            recorder.setOutputFile(f.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            segments.add(f);
            if (listener != null) {
                listener.onSegmentStarted(f, rolling.currentSegmentIndex());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            fail("recorder start failed: " + e.getMessage());
            return false;
        }
    }

    private void stopSegment() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "recorder stop after error", e);
        }
        recorder.release();
        recorder = null;
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(focus ->
                            handler.post(() -> {
                                if (focus == AudioManager.AUDIOFOCUS_LOSS
                                        || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                    pause();
                                    AppLogger.i(TAG, "audio focus lost, paused");
                                }
                            }))
                    .build();
            return audioManager.requestAudioFocus(focusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        //noinspection deprecation
        return audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(null);
        }
    }

    private long freeBytes() {
        try {
            StatFs fs = new StatFs(outputDir.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return Long.MAX_VALUE;
        }
    }

    private void setState(State s) {
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void fail(String message) {
        AppLogger.e(TAG, message);
        stopSegment();
        abandonFocus();
        setState(State.ERROR);
        if (listener != null) listener.onError(message);
    }
}
