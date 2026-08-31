package com.leslie.colorosnote.feature.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.leslie.colorosnote.core.common.AppLogger;

/**
 * Microphone capture for live transcription: AudioRecord at 16kHz mono PCM16
 * (exactly what Vosk consumes, no resampling needed) on its own thread, with
 * pause/resume support. Caller must hold RECORD_AUDIO permission before
 * {@link #start()}.
 */
public final class MicCapture {

    public interface PcmListener {
        /** Called on the capture thread. */
        void onPcm(short[] data, int length);

        /** Called on the capture thread. */
        void onError(String message);
    }

    private static final String TAG = "MicCapture";
    private static final int SAMPLE_RATE = PcmResampler.TARGET_RATE;

    private final PcmListener listener;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused;

    public MicCapture(PcmListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO
    public synchronized void start() {
        if (running) {
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE / 2;
        }
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        } catch (RuntimeException e) {
            listener.onError("无法打开麦克风：" + e.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            listener.onError("无法打开麦克风");
            return;
        }
        running = true;
        paused = false;
        final int chunkSize = Math.max(minBuf, SAMPLE_RATE / 5); // ~200ms chunks
        thread = new Thread(() -> captureLoop(chunkSize), "MicCapture");
        thread.start();
    }

    private void captureLoop(int chunkSize) {
        short[] buf = new short[chunkSize];
        try {
            record.startRecording();
        } catch (RuntimeException e) {
            running = false;
            listener.onError("录音启动失败：" + e.getMessage());
            return;
        }
        while (running) {
            if (paused) {
                synchronized (this) {
                    try {
                        record.stop();
                    } catch (RuntimeException ignored) {
                        // already stopped
                    }
                }
                while (running && paused) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    break;
                }
                synchronized (this) {
                    try {
                        record.startRecording();
                    } catch (RuntimeException e) {
                        running = false;
                        listener.onError("录音恢复失败：" + e.getMessage());
                        return;
                    }
                }
            }
            int n = record.read(buf, 0, buf.length);
            if (n > 0) {
                listener.onPcm(buf, n);
            } else if (n < 0) {
                AppLogger.e(TAG, "AudioRecord read error " + n);
            }
        }
        synchronized (this) {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
    }

    /** Pauses capture without discarding the session's committed text. */
    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (record != null) {
            record.release();
            record = null;
        }
    }
}
