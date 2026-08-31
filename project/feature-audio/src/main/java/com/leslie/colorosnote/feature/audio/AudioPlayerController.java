package com.leslie.colorosnote.feature.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.leslie.colorosnote.core.common.AppLogger;

import java.io.File;
import java.util.List;

/**
 * Media3 ExoPlayer wrapper (spec 20.2): seamless playback of rolling segments,
 * seek, 0.5x-2.0x speed, position callbacks for timeline sync.
 */
public final class AudioPlayerController {

    private static final String TAG = "AudioPlayer";

    public interface Listener {
        void onPosition(long positionMs, long durationMs);

        void onEnded();

        void onError(String message);
    }

    private final Context context;
    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean prepared;

    public AudioPlayerController(Context context) {
        this.context = context.getApplicationContext();
        player = new ExoPlayer.Builder(this.context).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && listener != null) {
                    listener.onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLogger.e(TAG, "playback error: " + error.getMessage());
                if (listener != null) listener.onError(error.getMessage());
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Load all rolling segments as one continuous stream. */
    public void loadSegments(List<File> segments) {
        if (segments == null || segments.isEmpty()) return;
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(context);
        ConcatenatingMediaSource concat = new ConcatenatingMediaSource();
        for (File f : segments) {
            concat.addMediaSource(new ProgressiveMediaSource.Factory(ds)
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(f))));
        }
        player.setMediaSource(concat);
        player.prepare();
        prepared = true;
    }

    public void play() {
        if (!prepared) return;
        player.play();
        handler.post(positionTicker);
    }

    public void pause() {
        player.pause();
        handler.removeCallbacks(positionTicker);
    }

    public void seekTo(long ms) {
        player.seekTo(ms);
    }

    public void setSpeed(float speed) {
        float clamped = Math.max(0.5f, Math.min(2.0f, speed));
        player.setPlaybackParameters(new PlaybackParameters(clamped));
    }

    public long positionMs() {
        return player.getCurrentPosition();
    }

    public long durationMs() {
        return player.getDuration();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void release() {
        handler.removeCallbacks(positionTicker);
        player.release();
    }

    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            if (listener != null) {
                listener.onPosition(player.getCurrentPosition(), player.getDuration());
            }
            handler.postDelayed(this, 250);
        }
    };
}
