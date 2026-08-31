package com.leslie.colorosnote.feature.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SegmentRollingPolicyTest {

    @Test
    public void firstTickStartsSegment() {
        SegmentRollingPolicy p = new SegmentRollingPolicy(1000);
        assertTrue(p.onTick(0));
        assertEquals(0, p.currentSegmentIndex());
    }

    @Test
    public void rollsAfterInterval() {
        SegmentRollingPolicy p = new SegmentRollingPolicy(1000);
        p.onTick(0);
        assertFalse(p.onTick(500));
        assertTrue(p.onTick(1000));
        assertEquals(1, p.currentSegmentIndex());
        assertTrue(p.onTick(2100));
        assertEquals(2, p.currentSegmentIndex());
    }

    @Test
    public void fileNames() {
        assertEquals("lecture_part007.m4a",
                SegmentRollingPolicy.segmentFileName("lecture", 7));
    }
}
