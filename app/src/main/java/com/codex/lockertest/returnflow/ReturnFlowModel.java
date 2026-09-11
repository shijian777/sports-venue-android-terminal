package com.codex.lockertest.returnflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure state machine for a server-authorized, sequential locker return. */
public final class ReturnFlowModel {
    public enum State {
        HOME,
        AUTHENTICATING,
        LOADING,
        SELECTING,
        CONFIRMING,
        OPENING,
        WAITING_FOR_CLOSE,
        COMMITTING,
        SUCCESS,
        FAILURE
    }

    public enum FailureCode {
        NONE,
        NO_LOCKERS
    }

    private long generation;
    private State state = State.HOME;
    private FailureCode failureCode = FailureCode.NONE;
    private ReturnIdentity identity;
    private List<ReturnLocker> remainingLockers = Collections.emptyList();
    private ReturnLocker selectedLocker;
    private ReturnAuthorization authorization;
    private long activeQueryRequestId;
    private long activeOperationId;
    private final Set<Long> usedOperationIds = new HashSet<>();

    public long beginReturn() {
        generation = nextGeneration(generation);
        state = State.AUTHENTICATING;
        failureCode = FailureCode.NONE;
        identity = null;
        remainingLockers = Collections.emptyList();
        selectedLocker = null;
        authorization = null;
        activeQueryRequestId = 0L;
        activeOperationId = 0L;
        usedOperationIds.clear();
        return generation;
    }

    public boolean acceptIdentity(long expectedGeneration, long queryRequestId, ReturnIdentity value) {
        if (!isCurrent(expectedGeneration) || state != State.AUTHENTICATING || value == null
                || queryRequestId <= 0L) {
            return false;
        }
        identity = value;
        activeQueryRequestId = queryRequestId;
        state = State.LOADING;
        return true;
    }

    public boolean acceptActiveLockers(long expectedGeneration, long queryRequestId,
            ReturnLockerList lockers) {
        if (!isCurrent(expectedGeneration) || state != State.LOADING || lockers == null
                || queryRequestId != activeQueryRequestId) {
            return false;
        }
        if (lockers.isEmpty()) {
            remainingLockers = Collections.emptyList();
            state = State.FAILURE;
            failureCode = FailureCode.NO_LOCKERS;
            return true;
        }
        remainingLockers = Collections.unmodifiableList(new ArrayList<>(lockers.lockers()));
        state = State.SELECTING;
        return true;
    }

    public boolean selectLocker(long expectedGeneration, long operationId, ReturnLocker locker) {
        if (!isCurrent(expectedGeneration) || state != State.SELECTING || locker == null
                || operationId <= 0L || usedOperationIds.contains(operationId)
                || !remainingLockers.contains(locker)) {
            return false;
        }
        selectedLocker = locker;
        activeOperationId = operationId;
        usedOperationIds.add(operationId);
        state = State.CONFIRMING;
        return true;
    }

    public boolean acceptAuthorization(long expectedGeneration, long operationId,
            ReturnAuthorization value, long now) {
        if (!isCurrent(expectedGeneration) || state != State.CONFIRMING || value == null
                || authorization != null
                || operationId != activeOperationId || value.operationId() != activeOperationId
                || !value.locker().equals(selectedLocker) || value.isExpiredAt(now)) {
            return false;
        }
        authorization = value;
        return true;
    }

    /**
     * Drops only an authority that has actually expired so the server may re-authorize the
     * same identity, selected locker, and operation. No other journey correlation changes.
     */
    public boolean clearExpiredAuthorizationForRetry(
            long expectedGeneration, long operationId, long now) {
        if (!isCurrent(expectedGeneration)
                || state != State.CONFIRMING
                || authorization == null
                || operationId != activeOperationId
                || authorization.operationId() != operationId
                || !authorization.isExpiredAt(now)) {
            return false;
        }
        authorization = null;
        return true;
    }

    /**
     * Discards an authority that has not entered unlock dispatch so the same correlated operation
     * can obtain a fresh server authority after a pre-physical failure.
     */
    public boolean clearAuthorizationBeforeUnlockRetry(
            long expectedGeneration, long operationId) {
        if (!isCurrent(expectedGeneration)
                || state != State.CONFIRMING
                || authorization == null
                || operationId != activeOperationId
                || authorization.operationId() != operationId) {
            return false;
        }
        authorization = null;
        return true;
    }

    public boolean beginUnlockDispatch(long expectedGeneration, long operationId, long now) {
        if (!isCurrent(expectedGeneration) || state != State.CONFIRMING || authorization == null
                || operationId != activeOperationId || authorization.isExpiredAt(now)) {
            return false;
        }
        state = State.OPENING;
        return true;
    }

    /** Restores CONFIRMING only when dispatch failed before any physical writer accepted it. */
    public boolean rollbackUnlockDispatchBeforeWrite(
            long expectedGeneration, long operationId) {
        if (!isCurrent(expectedGeneration)
                || state != State.OPENING
                || authorization == null
                || operationId != activeOperationId
                || authorization.operationId() != operationId) {
            return false;
        }
        state = State.CONFIRMING;
        return true;
    }

    public boolean markUnlockAccepted(long expectedGeneration, long operationId) {
        if (!isCurrent(expectedGeneration) || state != State.OPENING || authorization == null
                || operationId != activeOperationId) {
            return false;
        }
        state = State.WAITING_FOR_CLOSE;
        return true;
    }

    public boolean markStableDoorClose(long expectedGeneration, long operationId) {
        if (!isCurrent(expectedGeneration) || state != State.WAITING_FOR_CLOSE
                || operationId != activeOperationId) {
            return false;
        }
        state = State.COMMITTING;
        return true;
    }

    public boolean commitSuccess(long expectedGeneration, ReturnCompletionReceipt receipt) {
        if (!isCurrent(expectedGeneration) || state != State.COMMITTING || selectedLocker == null
                || receipt == null || receipt.operationId() != activeOperationId) {
            return false;
        }
        List<ReturnLocker> next = new ArrayList<>(remainingLockers);
        if (!next.remove(selectedLocker)) {
            return false;
        }
        remainingLockers = Collections.unmodifiableList(next);
        selectedLocker = null;
        authorization = null;
        activeOperationId = 0L;
        state = next.isEmpty() ? State.SUCCESS : State.SELECTING;
        return true;
    }

    public void cancelOrReset() {
        generation = nextGeneration(generation);
        state = State.HOME;
        failureCode = FailureCode.NONE;
        identity = null;
        remainingLockers = Collections.emptyList();
        selectedLocker = null;
        authorization = null;
        activeQueryRequestId = 0L;
        activeOperationId = 0L;
        usedOperationIds.clear();
    }

    public long generation() {
        return generation;
    }

    public State state() {
        return state;
    }

    public FailureCode failureCode() {
        return failureCode;
    }

    public ReturnIdentity identity() {
        return identity;
    }

    public List<ReturnLocker> remainingLockers() {
        return remainingLockers;
    }

    public ReturnLocker selectedLocker() {
        return selectedLocker;
    }

    public ReturnAuthorization authorization() {
        return authorization;
    }

    public long activeQueryRequestId() {
        return activeQueryRequestId;
    }

    public long activeOperationId() {
        return activeOperationId;
    }

    private boolean isCurrent(long expectedGeneration) {
        return expectedGeneration > 0L && expectedGeneration == generation;
    }

    private static long nextGeneration(long previous) {
        if (previous == Long.MAX_VALUE) {
            throw new IllegalStateException("Return generation exhausted");
        }
        return previous + 1L;
    }
}
