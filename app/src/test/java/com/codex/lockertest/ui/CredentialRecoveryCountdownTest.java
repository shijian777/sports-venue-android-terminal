package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CredentialRecoveryCountdownTest {
    @Test
    public void expiresOnlyAfterEightOneSecondTicks() {
        CredentialRecoveryCountdown countdown = new CredentialRecoveryCountdown(8);

        countdown.start();

        assertTrue(countdown.isActive());
        assertEquals(8, countdown.secondsRemaining());
        for (int expected = 7; expected >= 1; expected--) {
            assertTrue(countdown.tick());
            assertEquals(expected, countdown.secondsRemaining());
        }
        assertFalse(countdown.tick());
        assertFalse(countdown.isActive());
        assertEquals(0, countdown.secondsRemaining());
    }

    @Test
    public void aNewInvalidCredentialRestartsTheFullCountdown() {
        CredentialRecoveryCountdown countdown = new CredentialRecoveryCountdown(8);
        countdown.start();
        countdown.tick();
        countdown.tick();

        countdown.start();

        assertTrue(countdown.isActive());
        assertEquals(8, countdown.secondsRemaining());
    }

    @Test
    public void cancelPreventsAnyLaterTickFromCompletingAgain() {
        CredentialRecoveryCountdown countdown = new CredentialRecoveryCountdown(8);
        countdown.start();

        countdown.cancel();

        assertFalse(countdown.isActive());
        assertEquals(0, countdown.secondsRemaining());
        assertFalse(countdown.tick());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveDuration() {
        new CredentialRecoveryCountdown(0);
    }
}
