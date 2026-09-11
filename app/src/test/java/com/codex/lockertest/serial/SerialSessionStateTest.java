package com.codex.lockertest.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SerialSessionStateTest {
    @Test
    public void onlyOneOpenCanStartAndSendRequiresAnOpenPort() {
        SerialSessionState state = new SerialSessionState();
        assertTrue(state.beginOpen());
        assertFalse(state.beginOpen());
        assertFalse(state.canSend());
        state.markOpened();
        assertTrue(state.canSend());
        long closeToken = state.beginClose();
        assertTrue(closeToken > 0L);
        assertFalse(state.canSend());
        assertTrue(state.completeClose(closeToken, true));
        assertTrue(state.beginOpen());
    }

    @Test
    public void closeTimeoutKeepsSessionNonOpenableUntilExactReaderTerminates() {
        SerialSessionState state = openedState();
        long closeToken = state.beginClose();

        assertFalse(state.completeClose(closeToken, false));
        assertFalse(state.beginOpen());
        assertFalse(state.canSend());
        assertTrue(state.completeClose(closeToken, true));
        assertTrue(state.beginOpen());
    }

    @Test
    public void staleCloseCompletionCannotCloseAReplacementSession() {
        SerialSessionState state = openedState();
        long oldClose = state.beginClose();
        assertTrue(state.completeClose(oldClose, true));
        assertTrue(state.beginOpen());
        assertTrue(state.markOpened());
        assertTrue(state.canSend());

        assertFalse(state.completeClose(oldClose, true));
        assertTrue(state.canSend());
    }

    @Test
    public void failedOpenReturnsToClosedButDisposedNeverReopens() {
        SerialSessionState state = new SerialSessionState();
        assertTrue(state.beginOpen());
        state.markOpenFailed();
        assertTrue(state.beginOpen());
        state.markOpenFailed();
        state.dispose();
        assertFalse(state.beginOpen());
        assertFalse(state.canSend());
        assertTrue(state.beginClose() == 0L);
    }

    @Test
    public void disposeDuringOpenPreventsResourcePublication() {
        SerialSessionState state = new SerialSessionState();
        assertTrue(state.beginOpen());
        state.dispose();
        final boolean[] published = {false};
        assertFalse(state.commitOpen(() -> published[0] = true));
        assertFalse(published[0]);
        assertFalse(state.canSend());
    }

    @Test
    public void persistentClientReusesOpenPortOpensOnlyClosedAndWaitsForTransitions() {
        SerialSessionState state = new SerialSessionState();
        assertEquals(SerialSessionState.Phase.CLOSED, state.phase());
        assertEquals(SerialSessionState.ConnectionAction.OPEN, state.connectionAction());

        assertTrue(state.beginOpen());
        assertEquals(SerialSessionState.Phase.OPENING, state.phase());
        assertEquals(SerialSessionState.ConnectionAction.WAIT, state.connectionAction());

        assertTrue(state.markOpened());
        assertEquals(SerialSessionState.Phase.OPEN, state.phase());
        assertEquals(SerialSessionState.ConnectionAction.REUSE, state.connectionAction());

        long closeToken = state.beginClose();
        assertEquals(SerialSessionState.Phase.CLOSING, state.phase());
        assertEquals(SerialSessionState.ConnectionAction.WAIT, state.connectionAction());
        assertTrue(state.completeClose(closeToken, true));
        assertEquals(SerialSessionState.ConnectionAction.OPEN, state.connectionAction());

        state.dispose();
        assertEquals(SerialSessionState.Phase.DISPOSED, state.phase());
        assertEquals(SerialSessionState.ConnectionAction.UNAVAILABLE, state.connectionAction());
    }

    private static SerialSessionState openedState() {
        SerialSessionState state = new SerialSessionState();
        assertTrue(state.beginOpen());
        assertTrue(state.markOpened());
        return state;
    }
}
