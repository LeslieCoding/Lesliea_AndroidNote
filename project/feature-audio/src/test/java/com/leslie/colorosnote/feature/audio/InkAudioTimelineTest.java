package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class InkAudioTimelineTest {

    @Test
    public void attachAndLookup() {
        InkAudioTimeline tl = new InkAudioTimeline();
        tl.attach("s1", 1000, 2000);
        assertEquals(Long.valueOf(1500), tl.audioTimeForStroke("s1"));
        assertNull(tl.audioTimeForStroke("unknown"));
    }

    @Test
    public void offsetApplied() {
        InkAudioTimeline tl = new InkAudioTimeline();
        tl.attach("s1", 1000, 2000);
        tl.setOffsetMs(500);
        assertEquals(Long.valueOf(2000), tl.audioTimeForStroke("s1"));
    }

    @Test
    public void strokesNearFindsWindow() {
        InkAudioTimeline tl = new InkAudioTimeline();
        tl.attach("a", 0, 1000);
        tl.attach("b", 5000, 6000);
        tl.attach("c", 50000, 51000);
        List<InkAudioTimeline.StrokeTime> near = tl.strokesNear(5500, 1000);
        assertEquals(1, near.size());
        assertEquals("b", near.get(0).strokeId);
    }

    @Test
    public void snapshotRoundTrip() {
        InkAudioTimeline tl = new InkAudioTimeline();
        tl.attach("a", 0, 100);
        tl.setOffsetMs(42);
        InkAudioTimeline.Snapshot snap = tl.snapshot();

        InkAudioTimeline tl2 = new InkAudioTimeline();
        tl2.restore(snap);
        assertEquals(42, tl2.getOffsetMs());
        assertEquals(Long.valueOf(92), tl2.audioTimeForStroke("a"));
    }
}
