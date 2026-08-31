package com.leslie.colorosnote.feature.editor;

import java.util.Locale;

/**
 * Pure-JVM state machine behind the floating recording control bar
 * (spec 20): drives bar visibility, the elapsed-time text and the
 * pause/resume affordance from recorder events. Illegal transitions are
 * ignored so lifecycle- and focus-driven recorder callbacks can never leave
 * the bar inconsistent. All Android views are isolated behind {@link Ui}.
 *
 * <p>All methods run on the main thread.
 */
public final class RecordingBarState {

    public enum State { IDLE, RECORDING, PAUSED }

    public interface Ui {
        void showBar();

        void hideBar();

        /** {@code formatted} is the {@link #formatElapsed} output (no prefix). */
        void showElapsed(String formatted);

        /** True while paused: pause button shows 继续 and the timer goes dim. */
        void showPaused(boolean paused);
    }

    private final Ui ui;
    private State state = State.IDLE;

    public RecordingBarState(Ui ui) {
        this.ui = ui;
    }

    public State state() {
        return state;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public boolean isPaused() {
        return state == State.PAUSED;
    }

    public void onRecordingStarted() {
        if (state == State.RECORDING) {
            return;
        }
        state = State.RECORDING;
        ui.showBar();
        ui.showPaused(false);
        ui.showElapsed(formatElapsed(0));
    }

    public void onPaused() {
        if (state != State.RECORDING) {
            return;
        }
        state = State.PAUSED;
        ui.showPaused(true);
    }

    public void onResumed() {
        if (state != State.PAUSED) {
            return;
        }
        state = State.RECORDING;
        ui.showPaused(false);
    }

    public void onStopped() {
        if (state == State.IDLE) {
            return;
        }
        state = State.IDLE;
        ui.hideBar();
    }

    /** Recorder tick; ignored while paused so the timer visually freezes. */
    public void onElapsed(long elapsedMs) {
        if (state == State.RECORDING) {
            ui.showElapsed(formatElapsed(elapsedMs));
        }
    }

    /** {@code mm:ss}, or {@code h:mm:ss} past one hour. */
    public static String formatElapsed(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
