package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClockAdminTapGateTest {
    @Test
    public void fifthConsecutiveTapTriggersOnceAndStartsAFreshSequence() {
        ClockAdminTapGate gate = new ClockAdminTapGate();

        assertFalse(gate.tap(0L));
        assertFalse(gate.tap(100L));
        assertFalse(gate.tap(200L));
        assertFalse(gate.tap(300L));
        assertTrue(gate.tap(400L));
        assertFalse(gate.tap(500L));
    }

    @Test
    public void gapBeyondLimitRestartsWithTheCurrentTap() {
        ClockAdminTapGate gate = new ClockAdminTapGate();

        assertFalse(gate.tap(0L));
        assertFalse(gate.tap(100L));
        assertFalse(gate.tap(1602L));
        assertFalse(gate.tap(1700L));
        assertFalse(gate.tap(1800L));
        assertFalse(gate.tap(1900L));
        assertTrue(gate.tap(2000L));
    }

    @Test
    public void decreasingAndNegativeClockValuesClearProgress() {
        ClockAdminTapGate gate = new ClockAdminTapGate();

        assertFalse(gate.tap(100L));
        assertFalse(gate.tap(200L));
        assertFalse(gate.tap(199L));
        assertFalse(gate.tap(300L));
        assertFalse(gate.tap(400L));
        assertFalse(gate.tap(500L));
        assertFalse(gate.tap(-1L));
        assertFalse(gate.tap(600L));
        assertFalse(gate.tap(700L));
        assertFalse(gate.tap(800L));
        assertFalse(gate.tap(900L));
        assertTrue(gate.tap(1000L));
    }

    @Test
    public void explicitResetClearsPartialSequence() {
        ClockAdminTapGate gate = new ClockAdminTapGate();
        assertFalse(gate.tap(0L));
        assertFalse(gate.tap(100L));

        gate.reset();

        assertFalse(gate.tap(200L));
        assertFalse(gate.tap(300L));
        assertFalse(gate.tap(400L));
        assertFalse(gate.tap(500L));
        assertTrue(gate.tap(600L));
    }
}
