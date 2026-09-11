package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReturnDoorSessionTest {
    @Test
    public void closedBaselineMustPrecedeOneUnlockSentEvent() {
        ReturnDoorSession session = new ReturnDoorSession(7L, 41L, target(1));

        assertEquals(ReturnDoorSession.Event.REJECTED, session.markUnlockSent(7L, 41L));
        long pollId = beginSentPoll(session);
        assertEquals(ReturnDoorSession.Event.BASELINE_CLOSED,
                session.onBytes(7L, 41L, closed(1)));
        assertFalse(session.hasPollInFlight());
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_OPEN,
                session.markUnlockSent(7L, 41L));
        assertEquals(ReturnDoorSession.Event.REJECTED,
                session.markUnlockSent(7L, 41L));
        assertTrue(pollId > 0L);
    }

    @Test
    public void closedClosedWithoutObservedOpenNeverCompletes() {
        ReturnDoorSession session = openedSession();

        assertEquals(ReturnDoorSession.Event.WAITING_FOR_OPEN,
                observe(session, closed(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_OPEN,
                observe(session, closed(1)));
        assertFalse(session.isStableCloseObserved());
        assertFalse(session.isReadyToCommit());
    }

    @Test
    public void activePushOnlyRequestsPollAndCannotAdvancePhysicalState() {
        ReturnDoorSession session = openedSession();

        assertEquals(ReturnDoorSession.Event.REQUEST_IMMEDIATE_POLL,
                session.onBytes(7L, 41L, pushOpen(1)));
        assertFalse(session.isStableCloseObserved());
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_OPEN,
                observe(session, closed(1)));
    }

    @Test
    public void openThenTwoClosedRequiresAckAndUserConfirmation() {
        ReturnDoorSession session = openedSession();

        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, open(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, closed(1)));
        assertEquals(ReturnDoorSession.Event.STABLE_CLOSE,
                observe(session, closed(1)));
        assertFalse(session.isReadyToCommit());

        assertEquals(ReturnDoorSession.Event.REQUEST_IMMEDIATE_POLL,
                session.acknowledgeDoorClosed(7L, 41L));
        assertFalse(session.isReadyToCommit());
        assertEquals(ReturnDoorSession.Event.NONE,
                session.markUnlockAck(7L, 41L, true));
        assertFalse(session.isReadyToCommit());
        assertEquals(ReturnDoorSession.Event.READY_TO_COMMIT,
                observe(session, closed(1)));
        assertTrue(session.isReadyToCommit());
    }

    @Test
    public void acknowledgementBeforeStableCloseIsRejectedAndCannotArmCommitBoundary() {
        ReturnDoorSession session = openedSession();
        assertEquals(ReturnDoorSession.Event.NONE,
                session.markUnlockAck(7L, 41L, true));

        assertEquals(ReturnDoorSession.Event.REJECTED,
                session.acknowledgeDoorClosed(7L, 41L));
        assertFalse(session.isUserAcknowledgedClose());
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, open(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, closed(1)));
        assertEquals(ReturnDoorSession.Event.STABLE_CLOSE,
                observe(session, closed(1)));
        assertFalse(session.isReadyToCommit());

        assertEquals(ReturnDoorSession.Event.STABLE_CLOSE,
                observe(session, closed(1)));
        assertFalse(session.isReadyToCommit());
        assertEquals(ReturnDoorSession.Event.REQUEST_IMMEDIATE_POLL,
                session.acknowledgeDoorClosed(7L, 41L));
        assertEquals(ReturnDoorSession.Event.READY_TO_COMMIT,
                observe(session, closed(1)));
        assertTrue(session.isReadyToCommit());
    }

    @Test
    public void timeoutResetsCloseStreakAndStaleCorrelationIsIgnored() {
        ReturnDoorSession session = openedSession();
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, open(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, closed(1)));

        beginSentPoll(session);
        assertEquals(ReturnDoorSession.Event.STATUS_UNCERTAIN,
                session.onPollTimeout(7L, 41L));
        assertEquals(ReturnDoorSession.Event.REJECTED,
                session.onPollTimeout(6L, 41L));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, closed(1)));
        assertEquals(ReturnDoorSession.Event.STABLE_CLOSE,
                observe(session, closed(1)));
    }

    @Test
    public void wrongTargetAndUnsolicitedPollResponseCannotAdvanceSession() {
        ReturnDoorSession session = openedSession();

        assertEquals(ReturnDoorSession.Event.NONE,
                session.onBytes(7L, 41L, open(2)));
        assertEquals(ReturnDoorSession.Event.NONE,
                session.onBytes(7L, 41L, open(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE,
                observe(session, open(1)));
    }

    private static ReturnDoorSession openedSession() {
        ReturnDoorSession session = new ReturnDoorSession(7L, 41L, target(1));
        beginSentPoll(session);
        assertEquals(ReturnDoorSession.Event.BASELINE_CLOSED,
                session.onBytes(7L, 41L, closed(1)));
        assertEquals(ReturnDoorSession.Event.WAITING_FOR_OPEN,
                session.markUnlockSent(7L, 41L));
        return session;
    }

    private static long beginSentPoll(ReturnDoorSession session) {
        long pollId = session.beginPoll(7L, 41L);
        assertTrue(pollId > 0L);
        assertTrue(session.markPollSent(7L, 41L, pollId));
        return pollId;
    }

    private static ReturnDoorSession.Event observe(
            ReturnDoorSession session, byte[] response) {
        beginSentPoll(session);
        return session.onBytes(7L, 41L, response);
    }

    private static LockerTarget target(int lock) {
        return new LockerTarget(
                LockerZone.A, 1, lock, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static byte[] open(int lock) {
        return state((byte) 0x80, lock, (byte) 0x00);
    }

    private static byte[] closed(int lock) {
        return state((byte) 0x80, lock, (byte) 0x11);
    }

    private static byte[] pushOpen(int lock) {
        return state((byte) 0x82, lock, (byte) 0x00);
    }

    private static byte[] state(byte header, int lock, byte value) {
        byte board = 0x01;
        byte localLock = (byte) lock;
        return new byte[] {
                header, board, localLock, value,
                (byte) (header ^ board ^ localLock ^ value)
        };
    }
}
