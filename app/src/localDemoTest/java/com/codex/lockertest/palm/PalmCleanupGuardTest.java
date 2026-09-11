package com.codex.lockertest.palm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PalmCleanupGuardTest {
    @Test
    public void reportsRuntimeFailureWithoutEscaping() {
        boolean clean = PalmCleanupGuard.attempt(new PalmCleanupGuard.Action() {
            @Override public void run() {
                throw new IllegalStateException("cancel failed");
            }
        });

        assertFalse(clean);
    }

    @Test
    public void reportsSuccessfulCleanup() {
        final boolean[] called = {false};

        boolean clean = PalmCleanupGuard.attempt(new PalmCleanupGuard.Action() {
            @Override public void run() { called[0] = true; }
        });

        assertTrue(clean);
        assertTrue(called[0]);
    }
}
