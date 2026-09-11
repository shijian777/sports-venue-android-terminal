package com.codex.lockertest.ui;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.returnflow.ReturnAuthorization;
import com.codex.lockertest.returnflow.ReturnCompletionReceipt;
import com.codex.lockertest.returnflow.ReturnFlowController;
import com.codex.lockertest.returnflow.ReturnIdentity;
import com.codex.lockertest.returnflow.ReturnLocker;
import com.codex.lockertest.returnflow.ReturnLockerList;
import com.codex.lockertest.returnflow.ReturnServiceClient;
import com.codex.lockertest.returnflow.ReturnServiceResult;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation.RetryKind;
import com.codex.lockertest.ui.zip.ReturnScreenResolver;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure routing contracts for the existing return domain and ZIP screens 27-39. */
public final class ZipReturnScreenRoutingTest {
    @Test
    public void everyReturnAssetFrom27Through39HasAReachableTypedState() {
        Journey selecting = selectingJourney();
        Journey confirming = confirmingJourney();
        Journey opening = openingJourney();
        Journey waiting = waitingJourney(new FakeService(locker("server-a1", 1)));
        Journey success = completedJourney(false);
        Journey commitFailed = completedJourney(true);

        List<ReturnScreenPresentation> reachable = Arrays.asList(
                resolve(authenticatingSnapshot(), true).build(),
                resolve(authenticatingSnapshot(), true).serviceBusy(true).build(),
                resolve(authenticatingSnapshot(), true).authenticationFailed(true).build(),
                resolve(queryFailureSnapshot(ReturnServiceResult.Code.PERMANENT_FAILURE), true)
                        .build(),
                resolve(selecting.snapshot(), true).build(),
                resolve(selecting.snapshot(), true).chosenLocker(selecting.locker).build(),
                resolve(selecting.snapshot(), true).serviceBusy(true).build(),
                resolve(noLockersSnapshot(), true).build(),
                resolve(confirming.snapshot(), true).authorizedRequestPresent(true).build(),
                resolve(waiting.snapshot(), true).unlockConsumed(true).doorSessionPresent(true)
                        .authorizedRequestPresent(true).build(),
                resolve(waiting.snapshot(), true).unlockConsumed(true).doorSessionPresent(true)
                        .authorizedRequestPresent(true).doorProofReady(true)
                        .commitInFlight(true).build(),
                resolve(success.snapshot(), true).build(),
                resolve(commitFailed.snapshot(), true).unlockConsumed(true)
                        .doorSessionPresent(true).authorizedRequestPresent(true)
                        .doorProofReady(true).build());

        List<ZipScreenAsset> actual = new ArrayList<>();
        for (ReturnScreenPresentation presentation : reachable) {
            actual.add(presentation.asset());
        }
        assertEquals(Arrays.asList(
                ZipScreenAsset.RETURN_AUTH_READY,
                ZipScreenAsset.RETURN_AUTH_QUERYING,
                ZipScreenAsset.RETURN_AUTH_FAILED,
                ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR,
                ZipScreenAsset.RETURN_LOCKER_UNSELECTED,
                ZipScreenAsset.RETURN_LOCKER_SELECTED,
                ZipScreenAsset.RETURN_LOCKER_PROCESSING,
                ZipScreenAsset.RETURN_LOCKER_EMPTY,
                ZipScreenAsset.RETURN_OPENING,
                ZipScreenAsset.RETURN_WAITING_FOR_CLOSE,
                ZipScreenAsset.RETURN_COMMITTING,
                ZipScreenAsset.RETURN_SUCCESS,
                ZipScreenAsset.RETURN_FAILED), actual);
        assertEquals(13, new LinkedHashSet<>(actual).size());
        assertEquals(ZipScreenAsset.RETURN_OPENING,
                resolve(opening.snapshot(), true).unlockConsumed(false)
                        .authorizedRequestPresent(true).doorSessionPresent(true)
                        .build().asset());
    }

    @Test
    public void authenticationRoutingGivesOfflineAndTypedErrorsPriorityOverBusy() {
        ReturnFlowController.Snapshot ready = authenticatingSnapshot();
        ReturnFlowController.Snapshot retryable =
                queryFailureSnapshot(ReturnServiceResult.Code.RETRYABLE_FAILURE);
        ReturnFlowController.Snapshot unavailable =
                queryFailureSnapshot(ReturnServiceResult.Code.PERMANENT_FAILURE);

        ReturnScreenPresentation offlineBusy = resolve(ready, false)
                .serviceBusy(true).build();
        assertEquals(ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR, offlineBusy.asset());
        assertFalse(offlineBusy.canRetry());
        assertEquals(RetryKind.NONE, offlineBusy.retryKind());

        ReturnScreenPresentation offlineReady = resolve(ready, false).build();
        assertEquals(ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR, offlineReady.asset());
        assertTrue(offlineReady.canRetry());
        assertEquals(RetryKind.AUTH, offlineReady.retryKind());

        ReturnScreenPresentation localFailure = resolve(ready, true)
                .authenticationFailed(true).build();
        assertEquals(ZipScreenAsset.RETURN_AUTH_FAILED, localFailure.asset());
        assertEquals(RetryKind.AUTH, localFailure.retryKind());

        ReturnScreenPresentation queryFailure = resolve(retryable, true).build();
        assertEquals(ZipScreenAsset.RETURN_AUTH_FAILED, queryFailure.asset());
        assertEquals(RetryKind.QUERY, queryFailure.retryKind());

        ReturnScreenPresentation serviceUnavailable = resolve(unavailable, true).build();
        assertEquals(ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR,
                serviceUnavailable.asset());
        assertEquals(RetryKind.QUERY, serviceUnavailable.retryKind());
    }

    @Test
    public void chosenLockerMustRemainInTheServerSnapshotAndBusyBlocksDoubleTap() {
        Journey journey = selectingJourney();
        ReturnLocker disappeared = locker("not-in-current-snapshot", 2);

        ReturnScreenPresentation unselected = resolve(journey.snapshot(), true).build();
        assertEquals(ZipScreenAsset.RETURN_LOCKER_UNSELECTED, unselected.asset());
        assertTrue(unselected.canSelect());
        assertFalse(unselected.canConfirmLocker());
        assertTrue(unselected.canBack());

        ReturnScreenPresentation selected = resolve(journey.snapshot(), true)
                .chosenLocker(journey.locker).build();
        assertEquals(ZipScreenAsset.RETURN_LOCKER_SELECTED, selected.asset());
        assertTrue(selected.canSelect());
        assertTrue(selected.canConfirmLocker());

        ReturnScreenPresentation vanished = resolve(journey.snapshot(), true)
                .chosenLocker(disappeared).build();
        assertEquals(ZipScreenAsset.RETURN_LOCKER_UNSELECTED, vanished.asset());
        assertFalse(vanished.canConfirmLocker());

        ReturnScreenPresentation busy = resolve(journey.snapshot(), true)
                .chosenLocker(journey.locker).serviceBusy(true).build();
        assertEquals(ZipScreenAsset.RETURN_LOCKER_PROCESSING, busy.asset());
        assertNoAction(busy);

        Journey confirming = confirmingJourney();
        ReturnScreenPresentation noPhysicalSession =
                resolve(confirming.snapshot(), true).build();
        assertEquals(ZipScreenAsset.RETURN_LOCKER_PROCESSING,
                noPhysicalSession.asset());
        assertNoAction(noPhysicalSession);
    }

    @Test
    public void physicalErrorsBeatOpeningWaitingAndExplicitCommitBusy() {
        Journey confirming = confirmingJourney();
        Journey opening = openingJourney();
        Journey waiting = waitingJourney(new FakeService(locker("server-a1", 1)));

        assertEquals(ZipScreenAsset.RETURN_OPENING,
                resolve(confirming.snapshot(), true)
                        .authorizedRequestPresent(true).build().asset());
        assertEquals(ZipScreenAsset.RETURN_OPENING,
                resolve(opening.snapshot(), true).authorizedRequestPresent(true)
                        .doorSessionPresent(true).build().asset());
        assertEquals(ZipScreenAsset.RETURN_WAITING_FOR_CLOSE,
                resolve(waiting.snapshot(), true).unlockConsumed(true)
                        .authorizedRequestPresent(true).doorSessionPresent(true)
                        .build().asset());

        ReturnScreenPresentation explicitCommit = resolve(waiting.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).doorProofReady(true)
                .serviceBusy(true).build();
        assertEquals(ZipScreenAsset.RETURN_COMMITTING, explicitCommit.asset());
        assertNoAction(explicitCommit);

        ReturnScreenPresentation statusError = resolve(waiting.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).doorProofReady(true)
                .serviceBusy(true).serialFailure(true).build();
        assertEquals(ZipScreenAsset.RETURN_FAILED, statusError.asset());
        assertEquals(RetryKind.STATUS, statusError.retryKind());

        ReturnScreenPresentation deferredCommit = resolve(waiting.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).doorProofReady(true)
                .serviceBusy(true).commitDeferred(true).build();
        assertEquals(ZipScreenAsset.RETURN_FAILED, deferredCommit.asset());
        assertEquals(RetryKind.COMMIT, deferredCommit.retryKind());

        ReturnScreenPresentation openingFailure = resolve(opening.snapshot(), true)
                .authorizedRequestPresent(true).doorSessionPresent(true)
                .serialFailure(true).build();
        assertEquals(ZipScreenAsset.RETURN_FAILED, openingFailure.asset());
    }

    @Test
    public void failedPageUsesOnlyTypedRecoveryAndConsumedUnlockCannotEscape() {
        Journey authorizationFailure = authorizationFailureJourney();
        Journey statusFailure = waitingJourney(new FakeService(locker("server-a1", 1)));
        assertTrue(statusFailure.controller.markStatusUncertain(
                statusFailure.generation, statusFailure.operationId));
        Journey commitFailure = completedJourney(true);
        Journey unlockFailure = openingJourney();
        assertTrue(unlockFailure.controller.markUnlockFailed(
                unlockFailure.generation, unlockFailure.operationId));

        ReturnScreenPresentation authorization = resolve(
                authorizationFailure.snapshot(), true)
                .authorizationRetryable(true).build();
        ReturnScreenPresentation status = resolve(statusFailure.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).build();
        ReturnScreenPresentation commit = resolve(commitFailure.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).doorProofReady(true).build();
        ReturnScreenPresentation restartQuery = resolve(unlockFailure.snapshot(), true)
                .unlockConsumed(false).authorizedRequestPresent(true)
                .doorSessionPresent(true).serialFailure(true).build();
        ReturnScreenPresentation consumed = resolve(unlockFailure.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).serialFailure(true).build();
        ReturnScreenPresentation retryInFlight = resolve(
                authorizationFailure.snapshot(), true)
                .authorizationRetryable(true).serviceBusy(true).build();

        assertEquals(RetryKind.AUTHORIZATION, authorization.retryKind());
        assertEquals(RetryKind.STATUS, status.retryKind());
        assertEquals(RetryKind.COMMIT, commit.retryKind());
        assertEquals(RetryKind.QUERY, restartQuery.retryKind());
        assertEquals(RetryKind.NONE, consumed.retryKind());

        Set<RetryKind> page39Kinds = new LinkedHashSet<>(Arrays.asList(
                authorization.retryKind(), status.retryKind(), commit.retryKind(),
                restartQuery.retryKind(), consumed.retryKind()));
        assertEquals(new LinkedHashSet<>(Arrays.asList(
                RetryKind.AUTHORIZATION, RetryKind.STATUS, RetryKind.COMMIT,
                RetryKind.QUERY, RetryKind.NONE)), page39Kinds);
        for (ReturnScreenPresentation presentation : Arrays.asList(
                authorization, status, commit, restartQuery, consumed)) {
            assertEquals(ZipScreenAsset.RETURN_FAILED, presentation.asset());
            assertFalse(presentation.canBack());
            assertFalse(presentation.canCancel());
        }
        assertFalse(consumed.canRetry());
        assertFalse(consumed.canHome());
        assertTrue(restartQuery.canRetry());
        assertTrue(restartQuery.canHome());
        assertEquals(ZipScreenAsset.RETURN_FAILED, retryInFlight.asset());
        assertNoAction(retryInFlight);
    }

    @Test
    public void onlyNoLockersAndSuccessAutoReturnAndDoorConfirmationIsOneShot() {
        Journey waiting = waitingJourney(new FakeService(locker("server-a1", 1)));
        Journey success = completedJourney(false);

        ReturnScreenPresentation empty = resolve(noLockersSnapshot(), true).build();
        ReturnScreenPresentation completed = resolve(success.snapshot(), true).build();
        ReturnScreenPresentation closeReady = resolve(waiting.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).build();

        assertTrue(empty.autoHome());
        assertTrue(empty.canHome());
        assertTrue(completed.autoHome());
        assertTrue(completed.canHome());
        assertFalse(closeReady.autoHome());
        assertTrue(closeReady.canDoorCloseConfirm());

        assertTrue(waiting.controller.acknowledgeDoorClosed(
                waiting.generation, waiting.operationId));
        ReturnScreenPresentation acknowledged = resolve(waiting.snapshot(), true)
                .unlockConsumed(true).authorizedRequestPresent(true)
                .doorSessionPresent(true).build();
        assertEquals(ZipScreenAsset.RETURN_WAITING_FOR_CLOSE, acknowledged.asset());
        assertFalse(acknowledged.canDoorCloseConfirm());
    }

    @Test
    public void consumedUnlockInPrePhysicalStatesFailsClosedWithoutEntryActions() {
        Journey selecting = selectingJourney();
        ReturnFlowController.Snapshot loadingFailure =
                queryFailureSnapshot(ReturnServiceResult.Code.RETRYABLE_FAILURE);
        List<ReturnScreenPresentation> impossible = Arrays.asList(
                resolve(authenticatingSnapshot(), true)
                        .unlockConsumed(true).build(),
                resolve(loadingFailure, true)
                        .unlockConsumed(true).build(),
                resolve(selecting.snapshot(), true)
                        .unlockConsumed(true).build(),
                resolve(selecting.snapshot(), true)
                        .chosenLocker(selecting.locker)
                        .unlockConsumed(true).build(),
                resolve(noLockersSnapshot(), true)
                        .unlockConsumed(true).build());

        for (ReturnScreenPresentation presentation : impossible) {
            assertEquals(ZipScreenAsset.RETURN_FAILED, presentation.asset());
            assertNoAction(presentation);
        }
    }

    @Test
    public void finalExpiredGateReachesAuthorizationRetryForTheSameOperation() {
        ReturnLocker locker = locker("server-a1", 1);
        MutableClock clock = new MutableClock(100L);
        FakeService service = new FakeService(locker);
        service.authorizationExpiresAt = 105L;
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity()));
        assertTrue(controller.selectAndAuthorize(generation, 61L, locker));
        com.codex.lockertest.unlock.AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 61L);
        assertNotNull(request);
        clock.now = 105L;
        assertEquals(ReturnFlowController.UnlockDispatchValidation.AUTHORIZATION_EXPIRED,
                controller.validateUnlockDispatch(generation, 61L, request, true));
        assertTrue(controller.markUnlockAuthorizationExpiredBeforeWrite(
                generation, 61L));

        ReturnScreenPresentation failed = resolve(controller.snapshot(), true)
                .authorizationRetryable(true)
                .unlockConsumed(false)
                .build();
        assertEquals(ZipScreenAsset.RETURN_FAILED, failed.asset());
        assertEquals(RetryKind.AUTHORIZATION, failed.retryKind());
        assertTrue(failed.canRetry());
        assertTrue(failed.canHome());
        assertEquals(61L, controller.snapshot().operationId());
        assertEquals(locker, controller.snapshot().selectedLocker());
    }

    @Test(expected = IllegalArgumentException.class)
    public void homeStateIsRejectedInsteadOfInventingAReturnPage() {
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(), () -> 100L);
        resolve(controller.snapshot(), true).build();
    }

    private static ReturnScreenResolver.Input.Builder resolve(
            ReturnFlowController.Snapshot snapshot, boolean networkOnline) {
        return ReturnScreenResolver.Input.builder(snapshot, networkOnline);
    }

    private static ReturnFlowController.Snapshot authenticatingSnapshot() {
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(locker("server-a1", 1)), () -> 100L);
        controller.begin();
        return controller.snapshot();
    }

    private static ReturnFlowController.Snapshot queryFailureSnapshot(
            ReturnServiceResult.Code code) {
        FakeService service = new FakeService(locker("server-a1", 1));
        service.queryFailure = code;
        ReturnFlowController controller = new ReturnFlowController(service, () -> 100L);
        long generation = controller.begin();
        assertFalse(controller.authenticate(generation, identity()));
        return controller.snapshot();
    }

    private static ReturnFlowController.Snapshot noLockersSnapshot() {
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(), () -> 100L);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity()));
        return controller.snapshot();
    }

    private static Journey selectingJourney() {
        ReturnLocker locker = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(locker), () -> 100L);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity()));
        return new Journey(controller, generation, 0L, locker);
    }

    private static Journey confirmingJourney() {
        Journey journey = selectingJourney();
        journey.operationId = 41L;
        assertTrue(journey.controller.selectAndAuthorize(
                journey.generation, journey.operationId, journey.locker));
        return journey;
    }

    private static Journey authorizationFailureJourney() {
        ReturnLocker locker = locker("server-a1", 1);
        FakeService service = new FakeService(locker);
        service.authorizationFailure = ReturnServiceResult.Code.RETRYABLE_FAILURE;
        ReturnFlowController controller = new ReturnFlowController(service, () -> 100L);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity()));
        Journey journey = new Journey(controller, generation, 42L, locker);
        assertFalse(controller.selectAndAuthorize(generation, 42L, locker));
        return journey;
    }

    private static Journey openingJourney() {
        Journey journey = confirmingJourney();
        assertNotNull(journey.controller.beginUnlockDispatch(
                journey.generation, journey.operationId));
        return journey;
    }

    private static Journey waitingJourney(FakeService service) {
        ReturnLocker locker = service.lockers.get(0);
        ReturnFlowController controller = new ReturnFlowController(service, () -> 100L);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity()));
        Journey journey = new Journey(controller, generation, 51L, locker);
        assertTrue(controller.selectAndAuthorize(generation, 51L, locker));
        assertNotNull(controller.beginUnlockDispatch(generation, 51L));
        assertTrue(controller.markUnlockAccepted(generation, 51L));
        return journey;
    }

    private static Journey completedJourney(boolean failCommit) {
        ReturnLocker locker = locker("server-a1", 1);
        FakeService service = new FakeService(locker);
        service.commitFailure = failCommit;
        Journey journey = waitingJourney(service);
        assertTrue(journey.controller.acknowledgeDoorClosed(
                journey.generation, journey.operationId));
        assertTrue(journey.controller.markStableDoorClosed(
                journey.generation, journey.operationId));
        return journey;
    }

    private static void assertNoAction(ReturnScreenPresentation presentation) {
        assertFalse(presentation.canBack());
        assertFalse(presentation.canCancel());
        assertFalse(presentation.canRetry());
        assertFalse(presentation.canIdentity());
        assertFalse(presentation.canSelect());
        assertFalse(presentation.canConfirmLocker());
        assertFalse(presentation.canDoorCloseConfirm());
        assertFalse(presentation.canHome());
        assertEquals(RetryKind.NONE, presentation.retryKind());
    }

    private static ReturnIdentity identity() {
        return new ReturnIdentity(UnlockMethod.ID_CARD, "typed-test-identity", 90L);
    }

    private static ReturnLocker locker(String id, int localLock) {
        LockerTarget target = new LockerTarget(
                LockerZone.A, 1, localLock, FeedbackPolarity.SHORT_WHEN_LOCKED);
        return new ReturnLocker(id, "A" + localLock, "A区", target);
    }

    private static final class Journey {
        final ReturnFlowController controller;
        final long generation;
        final ReturnLocker locker;
        long operationId;

        Journey(ReturnFlowController controller, long generation,
                long operationId, ReturnLocker locker) {
            this.controller = controller;
            this.generation = generation;
            this.operationId = operationId;
            this.locker = locker;
        }

        ReturnFlowController.Snapshot snapshot() {
            return controller.snapshot();
        }
    }

    private static final class FakeService implements ReturnServiceClient {
        final List<ReturnLocker> lockers;
        ReturnServiceResult.Code queryFailure;
        ReturnServiceResult.Code authorizationFailure;
        boolean commitFailure;
        long authorizationExpiresAt = 10_000L;

        FakeService(ReturnLocker... lockers) {
            this.lockers = Collections.unmodifiableList(Arrays.asList(lockers));
        }

        @Override
        public ReturnServiceResult<ReturnLockerList> queryActiveLockers(
                ReturnIdentity identity, long requestId) {
            if (queryFailure != null) {
                return ReturnServiceResult.failure(queryFailure);
            }
            return ReturnServiceResult.success(ReturnLockerList.of(lockers));
        }

        @Override
        public ReturnServiceResult<ReturnAuthorization> authorize(
                ReturnIdentity identity, ReturnLocker locker, long operationId) {
            if (authorizationFailure != null) {
                return ReturnServiceResult.failure(authorizationFailure);
            }
            return ReturnServiceResult.success(new ReturnAuthorization(
                    operationId,
                    locker,
                    LockerProtocol.unlockCommand(locker.target()),
                    LockerProtocol.successFrame(locker.target()),
                    LockerProtocol.failureFrame(locker.target()),
                    "typed-test-completion-token-" + operationId,
                    authorizationExpiresAt));
        }

        @Override
        public ReturnServiceResult<ReturnCompletionReceipt> complete(
                ReturnAuthorization authorization) {
            if (commitFailure) {
                return ReturnServiceResult.failure(
                        ReturnServiceResult.Code.RETRYABLE_FAILURE);
            }
            return ReturnServiceResult.success(new ReturnCompletionReceipt(
                    authorization.operationId(), false));
        }
    }

    private static final class MutableClock implements ReturnFlowController.Clock {
        long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long now() {
            return now;
        }
    }
}
