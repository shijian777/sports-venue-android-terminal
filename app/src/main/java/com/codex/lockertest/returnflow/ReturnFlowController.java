package com.codex.lockertest.returnflow;

import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure coordinator for the server-authorized part of a return journey.
 *
 * <p>It deliberately owns no Android, camera, view, timer, or serial object. Serial dispatch and
 * physical-door monitoring feed correlated outcomes back through the methods below.</p>
 */
public final class ReturnFlowController {
    public interface Clock {
        long now();
    }

    public enum ErrorCode {
        NONE,
        NO_LOCKERS,
        REJECTED,
        RETRYABLE_FAILURE,
        SERVICE_UNAVAILABLE,
        AUTHORIZATION_EXPIRED,
        UNLOCK_FAILED,
        STATUS_UNCERTAIN,
        COMMIT_FAILED
    }

    public enum UnlockDispatchValidation {
        ALLOWED,
        NETWORK_OFFLINE,
        AUTHORIZATION_EXPIRED,
        REJECTED
    }

    /** Credential-free, token-free immutable presentation state. */
    public static final class Snapshot {
        private final long generation;
        private final ReturnFlowModel.State state;
        private final ErrorCode error;
        private final List<ReturnLocker> remainingLockers;
        private final ReturnLocker selectedLocker;
        private final long operationId;
        private final boolean userAcknowledgedClose;
        private final boolean stableCloseObserved;

        private Snapshot(ReturnFlowModel model, ErrorCode error,
                boolean userAcknowledgedClose, boolean stableCloseObserved) {
            generation = model.generation();
            state = model.state();
            this.error = error;
            remainingLockers = Collections.unmodifiableList(
                    new ArrayList<>(model.remainingLockers()));
            selectedLocker = model.selectedLocker();
            operationId = model.activeOperationId();
            this.userAcknowledgedClose = userAcknowledgedClose;
            this.stableCloseObserved = stableCloseObserved;
        }

        public long generation() {
            return generation;
        }

        public ReturnFlowModel.State state() {
            return state;
        }

        public ErrorCode error() {
            return error;
        }

        public List<ReturnLocker> remainingLockers() {
            return remainingLockers;
        }

        public ReturnLocker selectedLocker() {
            return selectedLocker;
        }

        public long operationId() {
            return operationId;
        }

        public boolean userAcknowledgedClose() {
            return userAcknowledgedClose;
        }

        public boolean stableCloseObserved() {
            return stableCloseObserved;
        }
    }

    private final ReturnServiceClient service;
    private final Clock clock;
    private final ReturnFlowModel model = new ReturnFlowModel();
    private ErrorCode error = ErrorCode.NONE;
    private long nextQueryRequestId;
    private boolean userAcknowledgedClose;
    private boolean stableCloseObserved;

    public ReturnFlowController(ReturnServiceClient service, Clock clock) {
        if (service == null || clock == null) {
            throw new IllegalArgumentException("Return controller dependencies are required");
        }
        this.service = service;
        this.clock = clock;
    }

    public synchronized long begin() {
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
        return model.beginReturn();
    }

    public synchronized boolean authenticate(
            long expectedGeneration, ReturnIdentity identity) {
        if (!isCurrent(expectedGeneration)
                || model.state() != ReturnFlowModel.State.AUTHENTICATING
                || identity == null) {
            return false;
        }
        long queryRequestId = nextQueryRequestId();
        if (!model.acceptIdentity(expectedGeneration, queryRequestId, identity)) {
            return false;
        }
        error = ErrorCode.NONE;
        return queryActiveLockersLocked(expectedGeneration);
    }

    public synchronized boolean retryQuery(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)
                || model.state() != ReturnFlowModel.State.LOADING
                || model.identity() == null
                || model.activeQueryRequestId() <= 0L
                || error == ErrorCode.NONE) {
            return false;
        }
        return queryActiveLockersLocked(expectedGeneration);
    }

    public synchronized boolean selectAndAuthorize(long expectedGeneration,
            long operationId, ReturnLocker locker) {
        if (!isCurrent(expectedGeneration)
                || !model.selectLocker(expectedGeneration, operationId, locker)) {
            return false;
        }
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
        return authorizeSelectedLocked(expectedGeneration, operationId);
    }

    public synchronized boolean retryAuthorization(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.CONFIRMING)
                || error == ErrorCode.NONE) {
            return false;
        }
        if (model.authorization() != null
                && (error != ErrorCode.AUTHORIZATION_EXPIRED
                        || !model.clearExpiredAuthorizationForRetry(
                                expectedGeneration, operationId, safeNow()))) {
            return false;
        }
        return authorizeSelectedLocked(expectedGeneration, operationId);
    }

    /**
     * Converts a network loss after authorization but before unlock dispatch into a typed,
     * same-operation authorization retry. The never-dispatched authority is not reusable.
     */
    public synchronized boolean markAuthorizationUnavailableBeforeUnlock(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.CONFIRMING)
                || !model.clearAuthorizationBeforeUnlockRetry(
                        expectedGeneration, operationId)) {
            return false;
        }
        error = ErrorCode.SERVICE_UNAVAILABLE;
        return true;
    }

    public synchronized AuthorizedUnlockRequest beginUnlockDispatch(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.CONFIRMING)
                || model.authorization() == null) {
            return null;
        }
        long now = safeNow();
        if (model.authorization().isExpiredAt(now)) {
            error = ErrorCode.AUTHORIZATION_EXPIRED;
            return null;
        }
        if (!model.beginUnlockDispatch(expectedGeneration, operationId, now)) {
            return null;
        }
        error = ErrorCode.NONE;
        return AuthorizedUnlockRequest.from(model.authorization());
    }

    public synchronized UnlockDispatchValidation validateUnlockDispatch(
            long expectedGeneration,
            long operationId,
            AuthorizedUnlockRequest request,
            boolean networkOnline) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.OPENING)
                || error != ErrorCode.NONE
                || request == null) {
            return UnlockDispatchValidation.REJECTED;
        }
        ReturnAuthorization authorization = model.authorization();
        if (!matchesAuthorization(authorization, request)) {
            return UnlockDispatchValidation.REJECTED;
        }
        if (!networkOnline) {
            return UnlockDispatchValidation.NETWORK_OFFLINE;
        }
        if (authorization.isExpiredAt(safeNow())) {
            return UnlockDispatchValidation.AUTHORIZATION_EXPIRED;
        }
        return UnlockDispatchValidation.ALLOWED;
    }

    /**
     * Preserves an exact final-gate expiry when no physical writer accepted the request.
     * Identity, locker, operation ID, and the expired authority remain correlated until retry.
     */
    public synchronized boolean markUnlockAuthorizationExpiredBeforeWrite(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.OPENING)
                || model.authorization() == null
                || !model.authorization().isExpiredAt(safeNow())
                || !model.rollbackUnlockDispatchBeforeWrite(
                        expectedGeneration, operationId)) {
            return false;
        }
        error = ErrorCode.AUTHORIZATION_EXPIRED;
        return true;
    }

    /** Returns the exact authority needed to establish a closed-door baseline. */
    public synchronized AuthorizedUnlockRequest authorizedRequestForStatusBaseline(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.CONFIRMING)
                || model.authorization() == null) {
            return null;
        }
        long now = safeNow();
        if (model.authorization().isExpiredAt(now)) {
            error = ErrorCode.AUTHORIZATION_EXPIRED;
            return null;
        }
        return AuthorizedUnlockRequest.from(model.authorization());
    }

    public synchronized boolean markUnlockAccepted(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.OPENING)
                || !model.markUnlockAccepted(expectedGeneration, operationId)) {
            return false;
        }
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
        return true;
    }

    public synchronized boolean markUnlockFailed(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.OPENING)) {
            return false;
        }
        error = ErrorCode.UNLOCK_FAILED;
        return true;
    }

    /**
     * Restarts lookup with the already-authenticated identity, but never reuses the failed
     * authorization or its operation ID.
     */
    public synchronized long restartAfterUnlockFailure(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.OPENING)
                || error != ErrorCode.UNLOCK_FAILED
                || model.identity() == null) {
            return 0L;
        }
        ReturnIdentity identity = model.identity();
        model.cancelOrReset();
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
        long freshGeneration = model.beginReturn();
        authenticate(freshGeneration, identity);
        return freshGeneration;
    }

    public synchronized boolean markStatusUncertain(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.WAITING_FOR_CLOSE)) {
            return false;
        }
        error = ErrorCode.STATUS_UNCERTAIN;
        return true;
    }

    public synchronized boolean resumeStatusMonitoring(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.WAITING_FOR_CLOSE)
                || error != ErrorCode.STATUS_UNCERTAIN) {
            return false;
        }
        error = ErrorCode.NONE;
        return true;
    }

    public synchronized boolean markStableDoorClosed(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.WAITING_FOR_CLOSE)
                || error == ErrorCode.STATUS_UNCERTAIN) {
            return false;
        }
        stableCloseObserved = true;
        if (userAcknowledgedClose) {
            commitIfReadyLocked(expectedGeneration, operationId);
        }
        return true;
    }

    public synchronized boolean acknowledgeDoorClosed(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.WAITING_FOR_CLOSE)
                || error == ErrorCode.STATUS_UNCERTAIN) {
            return false;
        }
        userAcknowledgedClose = true;
        return true;
    }

    public synchronized boolean retryCommit(
            long expectedGeneration, long operationId) {
        if (!matchesOperation(expectedGeneration, operationId,
                ReturnFlowModel.State.COMMITTING)
                || error != ErrorCode.COMMIT_FAILED) {
            return false;
        }
        return completeLocked(expectedGeneration, operationId);
    }

    public synchronized void cancel() {
        model.cancelOrReset();
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(model, error,
                userAcknowledgedClose, stableCloseObserved);
    }

    private boolean queryActiveLockersLocked(long expectedGeneration) {
        ReturnServiceResult<ReturnLockerList> result;
        try {
            result = service.queryActiveLockers(
                    model.identity(), model.activeQueryRequestId());
        } catch (RuntimeException | LinkageError ignored) {
            result = null;
        }
        if (result == null) {
            error = ErrorCode.SERVICE_UNAVAILABLE;
            return false;
        }
        if (!result.isSuccess()) {
            if (result.code() == ReturnServiceResult.Code.NO_LOCKERS) {
                boolean accepted = model.acceptActiveLockers(
                        expectedGeneration,
                        model.activeQueryRequestId(),
                        ReturnLockerList.of(Collections.<ReturnLocker>emptyList()));
                error = accepted ? ErrorCode.NO_LOCKERS : ErrorCode.REJECTED;
                return accepted;
            }
            error = mapServiceError(result.code(), false);
            return false;
        }
        ReturnLockerList list;
        try {
            list = result.value();
        } catch (RuntimeException ignored) {
            error = ErrorCode.SERVICE_UNAVAILABLE;
            return false;
        }
        if (!model.acceptActiveLockers(
                expectedGeneration, model.activeQueryRequestId(), list)) {
            error = ErrorCode.REJECTED;
            return false;
        }
        error = model.state() == ReturnFlowModel.State.FAILURE
                ? ErrorCode.NO_LOCKERS : ErrorCode.NONE;
        return true;
    }

    private boolean authorizeSelectedLocked(
            long expectedGeneration, long operationId) {
        ReturnServiceResult<ReturnAuthorization> result;
        try {
            result = service.authorize(
                    model.identity(), model.selectedLocker(), operationId);
        } catch (RuntimeException | LinkageError ignored) {
            result = null;
        }
        if (result == null) {
            error = ErrorCode.SERVICE_UNAVAILABLE;
            return false;
        }
        if (!result.isSuccess()) {
            error = mapServiceError(result.code(), false);
            return false;
        }
        ReturnAuthorization authorization;
        try {
            authorization = result.value();
        } catch (RuntimeException ignored) {
            error = ErrorCode.REJECTED;
            return false;
        }
        long now = safeNow();
        if (authorization.isExpiredAt(now)) {
            error = ErrorCode.AUTHORIZATION_EXPIRED;
            return false;
        }
        if (!model.acceptAuthorization(
                expectedGeneration, operationId, authorization, now)) {
            error = ErrorCode.REJECTED;
            return false;
        }
        error = ErrorCode.NONE;
        return true;
    }

    private void commitIfReadyLocked(long expectedGeneration, long operationId) {
        if (!userAcknowledgedClose || !stableCloseObserved
                || !model.markStableDoorClose(expectedGeneration, operationId)) {
            return;
        }
        completeLocked(expectedGeneration, operationId);
    }

    private boolean completeLocked(long expectedGeneration, long operationId) {
        ReturnAuthorization authorization = model.authorization();
        if (authorization == null || authorization.operationId() != operationId) {
            error = ErrorCode.COMMIT_FAILED;
            return false;
        }
        ReturnServiceResult<ReturnCompletionReceipt> result;
        try {
            result = service.complete(authorization);
        } catch (RuntimeException | LinkageError ignored) {
            result = null;
        }
        if (result == null || !result.isSuccess()) {
            error = ErrorCode.COMMIT_FAILED;
            return false;
        }
        ReturnCompletionReceipt receipt;
        try {
            receipt = result.value();
        } catch (RuntimeException ignored) {
            error = ErrorCode.COMMIT_FAILED;
            return false;
        }
        if (!model.commitSuccess(expectedGeneration, receipt)) {
            error = ErrorCode.COMMIT_FAILED;
            return false;
        }
        error = ErrorCode.NONE;
        userAcknowledgedClose = false;
        stableCloseObserved = false;
        return true;
    }

    private boolean matchesOperation(long expectedGeneration, long operationId,
            ReturnFlowModel.State expectedState) {
        return isCurrent(expectedGeneration)
                && operationId > 0L
                && model.activeOperationId() == operationId
                && model.state() == expectedState;
    }

    private static boolean matchesAuthorization(
            ReturnAuthorization authorization,
            AuthorizedUnlockRequest request) {
        if (authorization == null
                || request == null
                || authorization.operationId() != request.operationId()) {
            return false;
        }
        com.codex.lockertest.model.LockerTarget authorizedTarget =
                authorization.locker().target();
        com.codex.lockertest.model.LockerTarget requestTarget = request.target();
        return authorizedTarget.zone() == requestTarget.zone()
                && authorizedTarget.boardAddress() == requestTarget.boardAddress()
                && authorizedTarget.localLock() == requestTarget.localLock()
                && authorizedTarget.feedbackPolarity() == requestTarget.feedbackPolarity()
                && Arrays.equals(authorization.unlockCommand(), request.unlockCommand())
                && Arrays.equals(
                        authorization.expectedSuccessFrame(),
                        request.expectedSuccessFrame())
                && Arrays.equals(
                        authorization.expectedFailureFrame(),
                        request.expectedFailureFrame());
    }

    private boolean isCurrent(long expectedGeneration) {
        return expectedGeneration > 0L
                && model.generation() == expectedGeneration;
    }

    private long safeNow() {
        long now;
        try {
            now = clock.now();
        } catch (RuntimeException | LinkageError ignored) {
            now = Long.MAX_VALUE;
        }
        return Math.max(0L, now);
    }

    private long nextQueryRequestId() {
        if (nextQueryRequestId == Long.MAX_VALUE) {
            throw new IllegalStateException("Return query ID exhausted");
        }
        nextQueryRequestId++;
        return nextQueryRequestId;
    }

    private static ErrorCode mapServiceError(
            ReturnServiceResult.Code code, boolean commit) {
        if (commit) {
            return ErrorCode.COMMIT_FAILED;
        }
        if (code == ReturnServiceResult.Code.NO_LOCKERS) {
            return ErrorCode.NO_LOCKERS;
        }
        if (code == ReturnServiceResult.Code.RETRYABLE_FAILURE) {
            return ErrorCode.RETRYABLE_FAILURE;
        }
        if (code == ReturnServiceResult.Code.PERMANENT_FAILURE) {
            return ErrorCode.SERVICE_UNAVAILABLE;
        }
        return ErrorCode.REJECTED;
    }
}
