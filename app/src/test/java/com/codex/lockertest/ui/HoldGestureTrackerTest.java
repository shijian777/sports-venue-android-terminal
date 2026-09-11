package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HoldGestureTrackerTest {
    @Test
    public void lessThanFiveSecondsNeverTriggers() {
        HoldGestureTracker tracker = new HoldGestureTracker(5_000L, 18f);
        tracker.onDown(1_000L, 100f, 50f);

        assertFalse(tracker.onTime(5_999L));
    }

    @Test
    public void fiveSecondsTriggersExactlyOnce() {
        HoldGestureTracker tracker = new HoldGestureTracker(5_000L, 18f);
        tracker.onDown(1_000L, 100f, 50f);

        assertTrue(tracker.onTime(6_000L));
        assertFalse(tracker.onTime(6_001L));
        assertFalse(tracker.onTime(10_000L));
    }

    @Test
    public void materialMovementCancelsTheHold() {
        HoldGestureTracker tracker = new HoldGestureTracker(5_000L, 18f);
        tracker.onDown(0L, 100f, 50f);

        tracker.onMove(119f, 50f);

        assertFalse(tracker.onTime(5_000L));
    }

    @Test
    public void evenTheSmallestMovementCancelsTheHold() {
        HoldGestureTracker tracker = new HoldGestureTracker(5_000L, 18f);
        tracker.onDown(0L, 100f, 50f);

        tracker.onMove(100.01f, 50f);

        assertFalse(tracker.onTime(5_000L));
    }

    @Test
    public void releaseOrCancelBeforeDeadlinePreventsTriggering() {
        HoldGestureTracker released = new HoldGestureTracker(5_000L, 18f);
        released.onDown(0L, 10f, 10f);
        released.onUpOrCancel();
        assertFalse(released.onTime(5_000L));

        HoldGestureTracker cancelled = new HoldGestureTracker(5_000L, 18f);
        cancelled.onDown(0L, 10f, 10f);
        cancelled.onUpOrCancel();
        assertFalse(cancelled.onTime(8_000L));
    }

    @Test
    public void aNewPressCanTriggerAfterThePreviousGestureEnded() {
        HoldGestureTracker tracker = new HoldGestureTracker(5_000L, 18f);
        tracker.onDown(0L, 0f, 0f);
        tracker.onUpOrCancel();
        tracker.onDown(10_000L, 20f, 20f);

        assertTrue(tracker.onTime(15_000L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveHoldDuration() {
        new HoldGestureTracker(0L, 18f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeMovementTolerance() {
        new HoldGestureTracker(5_000L, -1f);
    }
}
