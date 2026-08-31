package com.leslie.colorosnote.core.storage.trash;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 15-day retention rule boundaries (requirement 25). */
public class TrashRetentionPolicyTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void notExpiredBeforeRetentionEnds() {
        assertFalse(TrashRetentionPolicy.isExpired(T0, T0));
        assertFalse(TrashRetentionPolicy.isExpired(T0, T0 + TrashRetentionPolicy.RETENTION_MS - 1));
    }

    @Test
    public void expiredAtAndAfterBoundary() {
        assertTrue(TrashRetentionPolicy.isExpired(T0, T0 + TrashRetentionPolicy.RETENTION_MS));
        assertTrue(TrashRetentionPolicy.isExpired(T0, T0 + 10 * TrashRetentionPolicy.RETENTION_MS));
    }

    @Test
    public void remainingDaysRoundsUpAndClampsAtZero() {
        assertEquals(15, TrashRetentionPolicy.remainingDays(T0, T0));
        // 14 days + 1 hour elapsed -> 1 full day left would be wrong; 15d-14d1h = 23h -> 1 day.
        assertEquals(1, TrashRetentionPolicy.remainingDays(T0,
                T0 + 14 * TrashRetentionPolicy.DAY_MS + 60 * 60 * 1000));
        assertEquals(0, TrashRetentionPolicy.remainingDays(T0, T0 + TrashRetentionPolicy.RETENTION_MS));
        assertEquals(0, TrashRetentionPolicy.remainingDays(T0, T0 + 2 * TrashRetentionPolicy.RETENTION_MS));
    }

    @Test
    public void expiryCutoffMatchesIsExpired() {
        long now = T0 + 20 * TrashRetentionPolicy.DAY_MS;
        long cutoff = TrashRetentionPolicy.expiryCutoffMs(now);
        // An entry deleted exactly at the cutoff is expired; 1ms later is not.
        assertTrue(TrashRetentionPolicy.isExpired(cutoff, now));
        assertFalse(TrashRetentionPolicy.isExpired(cutoff + 1, now));
    }
}
