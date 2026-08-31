package com.leslie.colorosnote.feature.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RecordingBarStateTest {

    /** Records every Ui callback in order for assertions. */
    private static final class FakeUi implements RecordingBarState.Ui {
        final List<String> events = new ArrayList<>();

        @Override
        public void showBar() {
            events.add("show");
        }

        @Override
        public void hideBar() {
            events.add("hide");
        }

        @Override
        public void showElapsed(String formatted) {
            events.add("elapsed:" + formatted);
        }

        @Override
        public void showPaused(boolean paused) {
            events.add("paused:" + paused);
        }
    }

    @Test
    public void startsIdle() {
        RecordingBarState bar = new RecordingBarState(new FakeUi());
        assertEquals(RecordingBarState.State.IDLE, bar.state());
        assertFalse(bar.isActive());
        assertFalse(bar.isPaused());
    }

    @Test
    public void startShowsBarAndResetsTimer() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onRecordingStarted();
        assertEquals(RecordingBarState.State.RECORDING, bar.state());
        assertTrue(bar.isActive());
        assertEquals(List.of("show", "paused:false", "elapsed:00:00"), ui.events);
    }

    @Test
    public void duplicateStartDoesNotReShowBar() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onRecordingStarted();
        bar.onRecordingStarted();
        assertEquals(3, ui.events.size());
    }

    @Test
    public void elapsedTicksOnlyWhileRecording() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onElapsed(5000); // idle: ignored
        bar.onRecordingStarted();
        ui.events.clear();
        bar.onElapsed(65_000);
        bar.onPaused();
        bar.onElapsed(70_000); // paused: timer freezes
        assertEquals(List.of("elapsed:01:05", "paused:true"), ui.events);
    }

    @Test
    public void pauseResumeFlow() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onRecordingStarted();
        ui.events.clear();
        bar.onPaused();
        assertTrue(bar.isPaused());
        bar.onPaused(); // repeat pause: no-op
        bar.onResumed();
        assertEquals(RecordingBarState.State.RECORDING, bar.state());
        bar.onResumed(); // repeat resume: no-op
        assertEquals(List.of("paused:true", "paused:false"), ui.events);
    }

    @Test
    public void illegalTransitionsAreIgnored() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onPaused();
        bar.onResumed();
        bar.onStopped();
        assertEquals(RecordingBarState.State.IDLE, bar.state());
        assertTrue(ui.events.isEmpty());
    }

    @Test
    public void stopHidesBarIdempotently() {
        FakeUi ui = new FakeUi();
        RecordingBarState bar = new RecordingBarState(ui);
        bar.onRecordingStarted();
        bar.onPaused();
        ui.events.clear();
        bar.onStopped();
        assertEquals(RecordingBarState.State.IDLE, bar.state());
        bar.onStopped(); // second stop (recorder callback + explicit): no-op
        assertEquals(List.of("hide"), ui.events);
    }

    @Test
    public void formatsElapsed() {
        assertEquals("00:00", RecordingBarState.formatElapsed(0));
        assertEquals("00:00", RecordingBarState.formatElapsed(-500)); // clamped
        assertEquals("00:59", RecordingBarState.formatElapsed(59_999));
        assertEquals("01:05", RecordingBarState.formatElapsed(65_000));
        assertEquals("1:02:05", RecordingBarState.formatElapsed(3_725_000));
        assertEquals("10:00:00", RecordingBarState.formatElapsed(36_000_000));
    }
}
