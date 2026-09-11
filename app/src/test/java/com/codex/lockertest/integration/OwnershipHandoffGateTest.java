package com.codex.lockertest.integration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OwnershipHandoffGateTest {
    @Test
    public void launchWaitsForReleaseCompletionAndRunsOnlyOnce() {
        OwnershipHandoffGate gate = new OwnershipHandoffGate();
        int[] launches = {0};
        long token = gate.begin(() -> launches[0]++);

        assertEquals(0, launches[0]);
        assertTrue(gate.complete(token));
        assertEquals(1, launches[0]);
        assertFalse(gate.complete(token));
        assertEquals(1, launches[0]);
    }

    @Test
    public void invalidationMakesAnOldReleaseCompletionHarmless() {
        OwnershipHandoffGate gate = new OwnershipHandoffGate();
        int[] oldLaunches = {0};
        int[] currentLaunches = {0};
        long oldToken = gate.begin(() -> oldLaunches[0]++);
        gate.invalidate();
        long currentToken = gate.begin(() -> currentLaunches[0]++);

        assertFalse(gate.complete(oldToken));
        assertEquals(0, oldLaunches[0]);
        assertTrue(gate.complete(currentToken));
        assertEquals(1, currentLaunches[0]);
    }
}
