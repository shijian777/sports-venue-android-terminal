package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IdCardEventDrainTest {
    @Test
    public void onlyScannerKeysFromTheSameDeviceAreDrainedInsideTheWindow() {
        IdCardEventDrain drain = new IdCardEventDrain();
        drain.begin(1_000L, 7, 500L);

        assertTrue(drain.shouldConsume(1_000L, 7, true));
        assertTrue(drain.shouldConsume(1_500L, 7, true));
        assertFalse(drain.shouldConsume(1_200L, 8, true));
        assertFalse(drain.shouldConsume(1_200L, 7, false));
        assertFalse(drain.shouldConsume(1_501L, 7, true));
    }

    @Test
    public void clearAndUnknownDeviceIdentityRemainFailClosed() {
        IdCardEventDrain drain = new IdCardEventDrain();
        drain.begin(10L, -1, 500L);
        assertTrue(drain.shouldConsume(100L, -1, true));
        assertFalse(drain.shouldConsume(100L, 0, true));

        drain.clear();

        assertFalse(drain.shouldConsume(100L, -1, true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeDrainDurationIsRejected() {
        new IdCardEventDrain().begin(0L, 1, -1L);
    }
}
