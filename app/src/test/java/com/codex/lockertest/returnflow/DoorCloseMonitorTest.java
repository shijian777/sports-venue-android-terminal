package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.DoorStateResponseDetector;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DoorCloseMonitorTest {
    @Test
    public void closedBaselineThenUnlockOpenAndTwoPollClosesCompletes() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = new DoorCloseMonitor(41L, target);

        assertEquals(DoorCloseMonitor.Outcome.BASELINE_CLOSED,
                monitor.observePollState(41L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_OPEN, monitor.markUnlockSent(41L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(41L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(41L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE,
                monitor.observePollState(41L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertTrue(monitor.isComplete());
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE, monitor.markUnlockSent(41L, target));
    }

    @Test
    public void openBeforeBaselineCannotAuthorizeUnlockOrCompleteTheOperation() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = new DoorCloseMonitor(42L, target);

        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN,
                monitor.observePollState(42L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.REJECTED, monitor.markUnlockSent(42L, target));
        assertEquals(DoorCloseMonitor.Outcome.BASELINE_CLOSED,
                monitor.observePollState(42L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_OPEN, monitor.markUnlockSent(42L, target));
        assertFalse(monitor.isComplete());
    }

    @Test
    public void repeatedClosedWithoutOpenAndAReopenedDoorCannotCompleteEarly() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(43L, target);

        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_OPEN,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_OPEN,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(43L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertFalse(monitor.isComplete());
    }

    @Test
    public void pushTimeoutDisconnectAndParserFailureNeverCompleteAndResumePreservesObservedOpen() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(44L, target);
        monitor.observePollState(44L, target, DoorStateResponseDetector.PhysicalState.OPEN);

        assertEquals(DoorCloseMonitor.Outcome.REQUEST_IMMEDIATE_POLL,
                monitor.observeActivePush(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.timeout(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.disconnect(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.parserError(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(44L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(44L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE,
                monitor.observePollState(44L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
    }

    @Test
    public void timeoutBetweenClosedPollsRequiresTwoFreshClosedPollsAfterResume() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(47L, target);
        monitor.observePollState(47L, target, DoorStateResponseDetector.PhysicalState.OPEN);
        monitor.observePollState(47L, target, DoorStateResponseDetector.PhysicalState.CLOSED);

        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.timeout(47L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(47L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(47L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE,
                monitor.observePollState(47L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
    }

    @Test
    public void disconnectBetweenClosedPollsRequiresTwoFreshClosedPollsAfterResume() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(48L, target);
        monitor.observePollState(48L, target, DoorStateResponseDetector.PhysicalState.OPEN);
        monitor.observePollState(48L, target, DoorStateResponseDetector.PhysicalState.CLOSED);

        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.disconnect(48L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(48L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(48L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE,
                monitor.observePollState(48L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
    }

    @Test
    public void parserErrorBetweenClosedPollsRequiresTwoFreshClosedPollsAfterResume() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(49L, target);
        monitor.observePollState(49L, target, DoorStateResponseDetector.PhysicalState.OPEN);
        monitor.observePollState(49L, target, DoorStateResponseDetector.PhysicalState.CLOSED);

        assertEquals(DoorCloseMonitor.Outcome.STATUS_UNCERTAIN, monitor.parserError(49L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE, monitor.resume(49L, target));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(49L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.COMPLETE,
                monitor.observePollState(49L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
    }

    @Test
    public void staleOperationOtherTargetUnknownAndCancelledFramesCannotChangeTheActiveState() {
        LockerTarget target = target(1);
        LockerTarget other = target(2);
        DoorCloseMonitor monitor = readyMonitor(45L, target);

        assertEquals(DoorCloseMonitor.Outcome.REJECTED,
                monitor.observePollState(44L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.REJECTED,
                monitor.observePollState(45L, other, DoorStateResponseDetector.PhysicalState.OPEN));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_OPEN,
                monitor.observePollState(45L, target, DoorStateResponseDetector.PhysicalState.UNKNOWN));
        assertEquals(DoorCloseMonitor.Outcome.CANCELLED, monitor.cancel(45L, target));
        assertEquals(DoorCloseMonitor.Outcome.REJECTED,
                monitor.observePollState(45L, target, DoorStateResponseDetector.PhysicalState.OPEN));
        assertFalse(monitor.isComplete());
    }

    @Test
    public void unknownDoesNotSupplyTheSecondClosedObservation() {
        LockerTarget target = target(1);
        DoorCloseMonitor monitor = readyMonitor(46L, target);
        monitor.observePollState(46L, target, DoorStateResponseDetector.PhysicalState.OPEN);
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(46L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(46L, target, DoorStateResponseDetector.PhysicalState.UNKNOWN));
        assertEquals(DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE,
                monitor.observePollState(46L, target, DoorStateResponseDetector.PhysicalState.CLOSED));
        assertFalse(monitor.isComplete());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveOperationIdIsRejectedAtConstruction() {
        new DoorCloseMonitor(0L, target(1));
    }

    private static DoorCloseMonitor readyMonitor(long operationId, LockerTarget target) {
        DoorCloseMonitor monitor = new DoorCloseMonitor(operationId, target);
        monitor.observePollState(operationId, target, DoorStateResponseDetector.PhysicalState.CLOSED);
        monitor.markUnlockSent(operationId, target);
        return monitor;
    }

    private static LockerTarget target(int localLock) {
        return new LockerTarget(LockerZone.A, LockerZone.A.boardAddress(), localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }
}
