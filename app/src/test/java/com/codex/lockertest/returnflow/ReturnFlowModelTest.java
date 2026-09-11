package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReturnFlowModelTest {
    @Test
    public void processesOneServerListedLockerThroughCloseAndCompletion() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker locker = locker("locker-7", 7);

        long generation = flow.beginReturn();
        assertEquals(ReturnFlowModel.State.AUTHENTICATING, flow.state());
        assertTrue(flow.acceptIdentity(generation, 71L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "identity-7", 10L)));
        assertTrue(flow.acceptActiveLockers(generation, 71L,
                ReturnLockerList.of(Collections.singletonList(locker))));
        assertEquals(ReturnFlowModel.State.SELECTING, flow.state());
        assertTrue(flow.selectLocker(generation, 91L, locker));
        assertEquals(ReturnFlowModel.State.CONFIRMING, flow.state());
        assertTrue(flow.acceptAuthorization(generation, 91L, authorization(91L, locker), 99L));
        assertTrue(flow.beginUnlockDispatch(generation, 91L, 99L));
        assertEquals(ReturnFlowModel.State.OPENING, flow.state());
        assertTrue(flow.markUnlockAccepted(generation, 91L));
        assertEquals(ReturnFlowModel.State.WAITING_FOR_CLOSE, flow.state());
        assertTrue(flow.markStableDoorClose(generation, 91L));
        assertEquals(ReturnFlowModel.State.COMMITTING, flow.state());
        assertTrue(flow.commitSuccess(generation, new ReturnCompletionReceipt(91L, false)));
        assertEquals(ReturnFlowModel.State.SUCCESS, flow.state());
    }

    @Test
    public void completionLeavesOnlyRemainingServerLockerForTheNextSequentialReturn() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker first = locker("locker-1", 1);
        ReturnLocker second = locker("locker-2", 2);
        long generation = readyToSelect(flow, first, second);

        assertTrue(flow.selectLocker(generation, 101L, first));
        assertTrue(flow.acceptAuthorization(generation, 101L, authorization(101L, first), 99L));
        assertTrue(flow.beginUnlockDispatch(generation, 101L, 99L));
        assertTrue(flow.markUnlockAccepted(generation, 101L));
        assertTrue(flow.markStableDoorClose(generation, 101L));
        assertTrue(flow.commitSuccess(generation, new ReturnCompletionReceipt(101L, false)));

        assertEquals(ReturnFlowModel.State.SELECTING, flow.state());
        assertEquals(Collections.singletonList(second), flow.remainingLockers());
        assertNull(flow.selectedLocker());
        assertNull(flow.authorization());
    }

    @Test
    public void staleCallbacksAndUnauthorizedSelectionLeaveTheSelectingSnapshotUntouched() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-3", 3);
        long generation = readyToSelect(flow, listed);

        assertFalse(flow.selectLocker(generation - 1L, 101L, listed));
        assertFalse(flow.selectLocker(generation, 101L, locker("not-listed", 4)));

        assertEquals(ReturnFlowModel.State.SELECTING, flow.state());
        assertEquals(Collections.singletonList(listed), flow.remainingLockers());
        assertNull(flow.selectedLocker());
    }

    @Test
    public void emptyServerResponseIsTypedNoLockerFailureAndResetInvalidatesItsGeneration() {
        ReturnFlowModel flow = new ReturnFlowModel();
        long generation = flow.beginReturn();
        assertTrue(flow.acceptIdentity(generation, 71L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "identity-empty", 10L)));
        assertTrue(flow.acceptActiveLockers(generation, 71L,
                ReturnLockerList.of(Collections.<ReturnLocker>emptyList())));

        assertEquals(ReturnFlowModel.State.FAILURE, flow.state());
        assertEquals(ReturnFlowModel.FailureCode.NO_LOCKERS, flow.failureCode());
        flow.cancelOrReset();
        assertEquals(ReturnFlowModel.State.HOME, flow.state());
        assertNull(flow.identity());
        assertTrue(flow.remainingLockers().isEmpty());
        assertFalse(flow.acceptActiveLockers(generation, 71L,
                ReturnLockerList.of(Collections.singletonList(locker("late", 5)))));
    }

    @Test
    public void staleOperationCallbacksCannotAdvanceTheNextLockerInTheSameGeneration() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker first = locker("locker-1", 1);
        ReturnLocker second = locker("locker-2", 2);
        long generation = flow.beginReturn();

        assertTrue(flow.acceptIdentity(generation, 81L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "correlated", 10L)));
        assertFalse(flow.acceptActiveLockers(generation, 80L,
                ReturnLockerList.of(Arrays.asList(first, second))));
        assertTrue(flow.acceptActiveLockers(generation, 81L,
                ReturnLockerList.of(Arrays.asList(first, second))));
        assertTrue(flow.selectLocker(generation, 101L, first));
        assertTrue(flow.acceptAuthorization(generation, 101L, authorization(101L, first), 50L));
        assertTrue(flow.beginUnlockDispatch(generation, 101L, 51L));
        assertTrue(flow.markUnlockAccepted(generation, 101L));
        assertTrue(flow.markStableDoorClose(generation, 101L));
        assertTrue(flow.commitSuccess(generation, new ReturnCompletionReceipt(101L, false)));
        Snapshot selectingSecond = new Snapshot(flow);
        assertFalse(flow.selectLocker(generation, 101L, second));
        selectingSecond.assertUnchanged(flow);
        assertTrue(flow.selectLocker(generation, 202L, second));
        assertTrue(flow.acceptAuthorization(generation, 202L, authorization(202L, second), 50L));
        assertTrue(flow.beginUnlockDispatch(generation, 202L, 51L));
        assertTrue(flow.markUnlockAccepted(generation, 202L));
        assertTrue(flow.markStableDoorClose(generation, 202L));

        assertFalse(flow.commitSuccess(generation, new ReturnCompletionReceipt(101L, false)));
        assertEquals(ReturnFlowModel.State.COMMITTING, flow.state());
        assertEquals(second, flow.selectedLocker());
        assertEquals(202L, flow.activeOperationId());
    }

    @Test
    public void wrongOrExpiredAuthorizationLeavesTheEntireConfirmingSnapshotUnchanged() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 101L, listed));
        Snapshot beforeWrongLocker = new Snapshot(flow);

        assertFalse(flow.acceptAuthorization(generation, 101L,
                authorization(101L, locker("other", 2)), 50L));
        beforeWrongLocker.assertUnchanged(flow);
        assertTrue(flow.acceptAuthorization(generation, 101L, authorization(101L, listed), 50L));
        Snapshot beforeExpiredDispatch = new Snapshot(flow);

        assertFalse(flow.beginUnlockDispatch(generation, 101L, 100L));
        beforeExpiredDispatch.assertUnchanged(flow);
    }

    @Test
    public void duplicateAuthorizationCannotReplaceTheFirstAcceptedAuthority() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 101L, listed));
        assertTrue(flow.acceptAuthorization(generation, 101L,
                authorization(101L, listed, "first-token", 60L), 50L));
        Snapshot accepted = new Snapshot(flow);

        assertFalse(flow.acceptAuthorization(generation, 101L,
                authorization(101L, listed, "replacement-token", 100L), 50L));
        accepted.assertUnchanged(flow);
    }

    @Test
    public void expiredAuthorityRetryClearsOnlyTheTokenAndKeepsIdentityLockerAndOperation() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 101L, listed));
        ReturnIdentity identity = flow.identity();
        ReturnAuthorization expired = authorization(
                101L, listed, "expired-token-must-not-be-reused", 60L);
        assertTrue(flow.acceptAuthorization(generation, 101L, expired, 50L));

        assertFalse(flow.clearExpiredAuthorizationForRetry(generation, 101L, 59L));
        assertTrue(expired == flow.authorization());
        assertTrue(flow.clearExpiredAuthorizationForRetry(generation, 101L, 60L));

        assertEquals(ReturnFlowModel.State.CONFIRMING, flow.state());
        assertEquals(generation, flow.generation());
        assertTrue(identity == flow.identity());
        assertEquals(listed, flow.selectedLocker());
        assertEquals(101L, flow.activeOperationId());
        assertNull(flow.authorization());
        assertFalse(flow.beginUnlockDispatch(generation, 101L, 60L));

        ReturnAuthorization refreshed = authorization(
                101L, listed, "fresh-token", 160L);
        assertTrue(flow.acceptAuthorization(generation, 101L, refreshed, 60L));
        assertTrue(refreshed == flow.authorization());
        assertFalse(expired == flow.authorization());
    }

    @Test
    public void preUnlockRetryCanDiscardOnlyTheNeverDispatchedAuthority() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 303L, listed));
        ReturnIdentity identity = flow.identity();
        ReturnAuthorization unused = authorization(
                303L, listed, "unused-token", 500L);
        assertTrue(flow.acceptAuthorization(generation, 303L, unused, 50L));

        assertTrue(flow.clearAuthorizationBeforeUnlockRetry(generation, 303L));

        assertEquals(ReturnFlowModel.State.CONFIRMING, flow.state());
        assertEquals(generation, flow.generation());
        assertTrue(identity == flow.identity());
        assertEquals(listed, flow.selectedLocker());
        assertEquals(303L, flow.activeOperationId());
        assertNull(flow.authorization());
        assertFalse(flow.beginUnlockDispatch(generation, 303L, 60L));
    }

    @Test
    public void preWriteDispatchRollbackKeepsTheExactCorrelationAndAuthority() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 304L, listed));
        ReturnIdentity identity = flow.identity();
        ReturnAuthorization authority = authorization(
                304L, listed, "expires-during-quiet-guard", 60L);
        assertTrue(flow.acceptAuthorization(generation, 304L, authority, 50L));
        assertTrue(flow.beginUnlockDispatch(generation, 304L, 51L));

        assertTrue(flow.rollbackUnlockDispatchBeforeWrite(generation, 304L));

        assertEquals(ReturnFlowModel.State.CONFIRMING, flow.state());
        assertEquals(generation, flow.generation());
        assertTrue(identity == flow.identity());
        assertEquals(listed, flow.selectedLocker());
        assertEquals(304L, flow.activeOperationId());
        assertTrue(authority == flow.authorization());
    }

    @Test
    public void initiallyExpiredAuthorizationLeavesTheConfirmingSnapshotUnchanged() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 101L, listed));
        Snapshot confirming = new Snapshot(flow);

        assertFalse(flow.acceptAuthorization(generation, 101L,
                authorization(101L, listed, "expired", 50L), 50L));
        confirming.assertUnchanged(flow);
    }

    @Test
    public void outOfOrderAndDuplicateCallbacksLeaveTheCurrentOperationUnchanged() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        long generation = readyToSelect(flow, listed);
        assertTrue(flow.selectLocker(generation, 101L, listed));
        assertTrue(flow.acceptAuthorization(generation, 101L, authorization(101L, listed), 50L));
        Snapshot confirming = new Snapshot(flow);

        assertFalse(flow.acceptAuthorization(generation, 102L, authorization(101L, listed), 50L));
        assertFalse(flow.markUnlockAccepted(generation, 101L));
        assertFalse(flow.commitSuccess(generation, new ReturnCompletionReceipt(101L, false)));
        confirming.assertUnchanged(flow);
        assertTrue(flow.beginUnlockDispatch(generation, 101L, 51L));
        assertFalse(flow.markUnlockAccepted(generation, 102L));
        assertTrue(flow.markUnlockAccepted(generation, 101L));
        Snapshot waiting = new Snapshot(flow);
        assertFalse(flow.markUnlockAccepted(generation, 101L));
        assertFalse(flow.markStableDoorClose(generation, 102L));
        assertFalse(flow.commitSuccess(generation, new ReturnCompletionReceipt(101L, false)));
        waiting.assertUnchanged(flow);
    }

    @Test
    public void listMutationAndResetCannotLeakOrRetainAnActiveAuthority() {
        ReturnFlowModel flow = new ReturnFlowModel();
        ReturnLocker listed = locker("locker-1", 1);
        java.util.ArrayList<ReturnLocker> callerList = new java.util.ArrayList<>();
        callerList.add(listed);
        ReturnLockerList payload = ReturnLockerList.of(callerList);
        callerList.clear();
        long generation = flow.beginReturn();
        assertTrue(flow.acceptIdentity(generation, 71L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "reset", 10L)));
        assertTrue(flow.acceptActiveLockers(generation, 71L, payload));
        assertTrue(flow.selectLocker(generation, 101L, listed));
        assertTrue(flow.acceptAuthorization(generation, 101L, authorization(101L, listed), 50L));

        try {
            flow.remainingLockers().clear();
            throw new AssertionError("Model snapshot must not be mutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(Collections.singletonList(listed), flow.remainingLockers());
        }
        flow.cancelOrReset();
        assertEquals(ReturnFlowModel.State.HOME, flow.state());
        assertEquals(ReturnFlowModel.FailureCode.NONE, flow.failureCode());
        assertNull(flow.identity());
        assertNull(flow.selectedLocker());
        assertNull(flow.authorization());
        assertEquals(0L, flow.activeQueryRequestId());
        assertEquals(0L, flow.activeOperationId());
        assertFalse(flow.beginUnlockDispatch(generation, 101L, 51L));
    }

    @Test
    public void nullAndIllegalCallbacksPreserveTheLoadingSnapshot() {
        ReturnFlowModel flow = new ReturnFlowModel();
        long generation = flow.beginReturn();
        assertTrue(flow.acceptIdentity(generation, 71L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "nulls", 10L)));
        Snapshot loading = new Snapshot(flow);

        assertFalse(flow.acceptActiveLockers(generation, 71L, null));
        assertFalse(flow.acceptActiveLockers(generation, 0L,
                ReturnLockerList.of(Collections.<ReturnLocker>emptyList())));
        loading.assertUnchanged(flow);
    }

    @Test
    public void nullCallbacksAtEveryActiveStageLeaveTheWholeSnapshotUnchanged() {
        ReturnFlowModel flow = new ReturnFlowModel();
        long generation = readyToSelect(flow, locker("locker-1", 1));
        Snapshot selecting = new Snapshot(flow);
        assertFalse(flow.selectLocker(generation, 101L, null));
        selecting.assertUnchanged(flow);
        assertTrue(flow.selectLocker(generation, 101L, locker("locker-1", 1)));
        Snapshot confirming = new Snapshot(flow);
        assertFalse(flow.acceptAuthorization(generation, 101L, null, 50L));
        confirming.assertUnchanged(flow);
        assertTrue(flow.acceptAuthorization(generation, 101L,
                authorization(101L, locker("locker-1", 1)), 50L));
        assertTrue(flow.beginUnlockDispatch(generation, 101L, 51L));
        assertTrue(flow.markUnlockAccepted(generation, 101L));
        assertTrue(flow.markStableDoorClose(generation, 101L));
        Snapshot committing = new Snapshot(flow);
        assertFalse(flow.commitSuccess(generation, null));
        committing.assertUnchanged(flow);
    }

    private static ReturnAuthorization authorization(ReturnLocker locker) {
        return authorization(7L, locker);
    }

    private static ReturnAuthorization authorization(long operationId, ReturnLocker locker) {
        return authorization(operationId, locker, "complete-" + locker.target().localLock(), 100L);
    }

    private static ReturnAuthorization authorization(long operationId, ReturnLocker locker,
            String token, long expiresAt) {
        if (locker.target().localLock() == 1) {
            return new ReturnAuthorization(operationId, locker,
                    new byte[] {(byte) 0x8A, 1, 1, 0x11, (byte) 0x9B},
                    new byte[] {(byte) 0x8A, 1, 1, 0, (byte) 0x8A},
                    new byte[] {(byte) 0x8A, 1, 1, 0x11, (byte) 0x9B},
                    token, expiresAt);
        }
        if (locker.target().localLock() == 2) {
            return new ReturnAuthorization(operationId, locker,
                    new byte[] {(byte) 0x8A, 1, 2, 0x11, (byte) 0x98},
                    new byte[] {(byte) 0x8A, 1, 2, 0, (byte) 0x89},
                    new byte[] {(byte) 0x8A, 1, 2, 0x11, (byte) 0x98},
                    token, expiresAt);
        }
        return new ReturnAuthorization(operationId, locker,
                new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D},
                new byte[] {(byte) 0x8A, 1, 7, 0, (byte) 0x8C},
                new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D},
                token, expiresAt);
    }

    private static long readyToSelect(ReturnFlowModel flow, ReturnLocker... lockers) {
        long generation = flow.beginReturn();
        assertTrue(flow.acceptIdentity(generation, 71L,
                new ReturnIdentity(UnlockMethod.ID_CARD, "identity-ready", 10L)));
        assertTrue(flow.acceptActiveLockers(generation, 71L,
                ReturnLockerList.of(Arrays.asList(lockers))));
        return generation;
    }

    private static ReturnLocker locker(String id, int localLock) {
        return new ReturnLocker(id, "A-" + localLock, "Lobby", new LockerTarget(
                LockerZone.A, LockerZone.A.boardAddress(), localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED));
    }

    private static final class Snapshot {
        private final ReturnFlowModel.State state;
        private final long generation;
        private final long queryId;
        private final long operationId;
        private final ReturnIdentity identity;
        private final java.util.List<ReturnLocker> lockers;
        private final ReturnLocker selected;
        private final ReturnAuthorization authorization;
        private final ReturnFlowModel.FailureCode failure;

        private Snapshot(ReturnFlowModel flow) {
            state = flow.state();
            generation = flow.generation();
            queryId = flow.activeQueryRequestId();
            operationId = flow.activeOperationId();
            identity = flow.identity();
            lockers = flow.remainingLockers();
            selected = flow.selectedLocker();
            authorization = flow.authorization();
            failure = flow.failureCode();
        }

        private void assertUnchanged(ReturnFlowModel flow) {
            assertEquals(state, flow.state());
            assertEquals(generation, flow.generation());
            assertEquals(queryId, flow.activeQueryRequestId());
            assertEquals(operationId, flow.activeOperationId());
            assertEquals(identity, flow.identity());
            assertEquals(lockers, flow.remainingLockers());
            assertEquals(selected, flow.selectedLocker());
            assertEquals(authorization, flow.authorization());
            assertEquals(failure, flow.failureCode());
        }
    }
}
