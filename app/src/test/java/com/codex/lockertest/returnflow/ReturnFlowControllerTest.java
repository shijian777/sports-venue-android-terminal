package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReturnFlowControllerTest {
    @Test
    public void oneLockerNeedsUnlockAckStableCloseAndUserAcknowledgementBeforeCommit() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        ReturnFlowController controller = new ReturnFlowController(service, clock);

        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertEquals(ReturnFlowModel.State.SELECTING, controller.snapshot().state());
        assertTrue(controller.selectAndAuthorize(generation, 41L, a1));

        AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 41L);
        assertNotNull(request);
        assertArrayEquals(LockerProtocol.unlockCommand(a1.target()), request.unlockCommand());
        assertTrue(controller.markUnlockAccepted(generation, 41L));
        assertEquals(ReturnFlowModel.State.WAITING_FOR_CLOSE, controller.snapshot().state());

        assertTrue(controller.markStableDoorClosed(generation, 41L));
        assertEquals(ReturnFlowModel.State.WAITING_FOR_CLOSE, controller.snapshot().state());
        assertEquals(0, service.completeCalls);
        assertTrue(controller.acknowledgeDoorClosed(generation, 41L));

        assertEquals(ReturnFlowModel.State.WAITING_FOR_CLOSE, controller.snapshot().state());
        assertEquals(0, service.completeCalls);
        assertTrue(controller.markStableDoorClosed(generation, 41L));

        assertEquals(ReturnFlowModel.State.SUCCESS, controller.snapshot().state());
        assertEquals(1, service.completeCalls);
    }

    @Test
    public void userAcknowledgementAloneCannotCompleteOrCallService() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        ReturnFlowController controller = waiting(controller(service, clock), a1, 51L);
        long generation = controller.snapshot().generation();

        assertTrue(controller.acknowledgeDoorClosed(generation, 51L));

        assertEquals(ReturnFlowModel.State.WAITING_FOR_CLOSE, controller.snapshot().state());
        assertTrue(controller.snapshot().userAcknowledgedClose());
        assertFalse(controller.snapshot().stableCloseObserved());
        assertEquals(0, service.completeCalls);
    }

    @Test
    public void multiLockerCompletionReturnsToServerListForNextLocker() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        ReturnLocker a2 = locker("server-a2", 2);
        FakeService service = new FakeService(a1, a2);
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("qr")));
        assertTrue(controller.selectAndAuthorize(generation, 61L, a1));
        assertNotNull(controller.beginUnlockDispatch(generation, 61L));
        assertTrue(controller.markUnlockAccepted(generation, 61L));
        assertTrue(controller.acknowledgeDoorClosed(generation, 61L));
        assertTrue(controller.markStableDoorClosed(generation, 61L));

        ReturnFlowController.Snapshot snapshot = controller.snapshot();
        assertEquals(ReturnFlowModel.State.SELECTING, snapshot.state());
        assertEquals(Collections.singletonList(a2), snapshot.remainingLockers());
        assertNull(snapshot.selectedLocker());
        assertEquals(0L, snapshot.operationId());
    }

    @Test
    public void arbitraryLockerAndEveryStaleCallbackAreRejectedWithoutMutation() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker listed = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(listed), clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        ReturnFlowController.Snapshot selecting = controller.snapshot();

        assertFalse(controller.selectAndAuthorize(
                generation, 71L, locker("not-server-listed", 2)));
        assertSnapshotEquals(selecting, controller.snapshot());
        assertFalse(controller.selectAndAuthorize(generation - 1L, 71L, listed));
        assertSnapshotEquals(selecting, controller.snapshot());

        assertTrue(controller.selectAndAuthorize(generation, 71L, listed));
        assertNotNull(controller.beginUnlockDispatch(generation, 71L));
        ReturnFlowController.Snapshot opening = controller.snapshot();
        assertFalse(controller.markUnlockAccepted(generation, 72L));
        assertFalse(controller.markStableDoorClosed(generation, 71L));
        assertFalse(controller.acknowledgeDoorClosed(generation - 1L, 71L));
        assertSnapshotEquals(opening, controller.snapshot());
    }

    @Test
    public void expiredAuthorizationNeverProducesSerialAuthority() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.authorizationLifetimeMillis = 5L;
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 81L, a1));
        clock.now = 105L;

        assertNull(controller.beginUnlockDispatch(generation, 81L));
        assertEquals(ReturnFlowController.ErrorCode.AUTHORIZATION_EXPIRED,
                controller.snapshot().error());
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
    }

    @Test
    public void expiredAuthorizationRetryKeepsSameLockerAndOperationWithoutUnlockDispatch() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.authorizationLifetimeMillis = 5L;
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 815L, a1));
        ReturnAuthorization expired = service.issuedAuthorizations.get(0);
        clock.now = 105L;
        assertNull(controller.beginUnlockDispatch(generation, 815L));
        assertEquals(ReturnFlowController.ErrorCode.AUTHORIZATION_EXPIRED,
                controller.snapshot().error());

        service.authorizationLifetimeMillis = 1_000L;
        assertTrue(controller.retryAuthorization(generation, 815L));

        ReturnFlowController.Snapshot refreshed = controller.snapshot();
        assertEquals(generation, refreshed.generation());
        assertEquals(ReturnFlowModel.State.CONFIRMING, refreshed.state());
        assertEquals(a1, refreshed.selectedLocker());
        assertEquals(815L, refreshed.operationId());
        assertEquals(ReturnFlowController.ErrorCode.NONE, refreshed.error());
        assertEquals(2, service.authorizeCalls);
        ReturnAuthorization fresh = service.issuedAuthorizations.get(1);
        assertEquals(expired.operationId(), fresh.operationId());
        assertEquals(expired.locker(), fresh.locker());
        assertFalse(expired.completionToken().equals(fresh.completionToken()));
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
        assertNotNull(controller.authorizedRequestForStatusBaseline(generation, 815L));
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
    }

    @Test
    public void offlineAfterAuthorizationBecomesSameOperationAuthorizationRetry() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        ReturnIdentity identity = identity("card");
        assertTrue(controller.authenticate(generation, identity));
        assertTrue(controller.selectAndAuthorize(generation, 816L, a1));
        ReturnAuthorization first = service.issuedAuthorizations.get(0);

        assertTrue(controller.markAuthorizationUnavailableBeforeUnlock(
                generation, 816L));

        ReturnFlowController.Snapshot failed = controller.snapshot();
        assertEquals(ReturnFlowModel.State.CONFIRMING, failed.state());
        assertEquals(ReturnFlowController.ErrorCode.SERVICE_UNAVAILABLE,
                failed.error());
        assertEquals(generation, failed.generation());
        assertEquals(a1, failed.selectedLocker());
        assertEquals(816L, failed.operationId());
        assertNull(controller.beginUnlockDispatch(generation, 816L));

        assertTrue(controller.retryAuthorization(generation, 816L));
        ReturnAuthorization refreshed = service.issuedAuthorizations.get(1);
        assertEquals(2, service.authorizeCalls);
        assertEquals(first.operationId(), refreshed.operationId());
        assertEquals(first.locker(), refreshed.locker());
        assertFalse(first.completionToken().equals(refreshed.completionToken()));
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
        assertEquals(ReturnFlowController.ErrorCode.NONE, controller.snapshot().error());
    }

    @Test
    public void finalExpiredGateRollsBackToAuthorizationRetryWithoutUnlockFailure() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.authorizationLifetimeMillis = 5L;
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 817L, a1));
        AuthorizedUnlockRequest dispatch =
                controller.beginUnlockDispatch(generation, 817L);
        assertNotNull(dispatch);
        clock.now = 105L;
        assertEquals(ReturnFlowController.UnlockDispatchValidation.AUTHORIZATION_EXPIRED,
                controller.validateUnlockDispatch(
                        generation, 817L, dispatch, true));

        assertTrue(controller.markUnlockAuthorizationExpiredBeforeWrite(
                generation, 817L));

        ReturnFlowController.Snapshot failed = controller.snapshot();
        assertEquals(ReturnFlowModel.State.CONFIRMING, failed.state());
        assertEquals(ReturnFlowController.ErrorCode.AUTHORIZATION_EXPIRED,
                failed.error());
        assertEquals(generation, failed.generation());
        assertEquals(a1, failed.selectedLocker());
        assertEquals(817L, failed.operationId());
        assertFalse(controller.markUnlockFailed(generation, 817L));
        assertEquals(ReturnFlowController.ErrorCode.AUTHORIZATION_EXPIRED,
                controller.snapshot().error());
        assertNull(controller.beginUnlockDispatch(generation, 817L));

        service.authorizationLifetimeMillis = 1_000L;
        assertTrue(controller.retryAuthorization(generation, 817L));
        assertEquals(2, service.authorizeCalls);
        assertEquals(817L, service.issuedAuthorizations.get(1).operationId());
        assertEquals(a1, service.issuedAuthorizations.get(1).locker());
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
    }

    @Test
    public void finalDispatchGateRejectsNetworkLossAfterCoordinatorStart() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(a1), clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 811L, a1));
        AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 811L);
        assertNotNull(request);

        assertEquals(ReturnFlowController.UnlockDispatchValidation.NETWORK_OFFLINE,
                controller.validateUnlockDispatch(
                        generation, 811L, request, false));
        assertEquals(ReturnFlowModel.State.OPENING, controller.snapshot().state());
    }

    @Test
    public void finalDispatchGateAcceptsOnlyCurrentOnlineUnexpiredAuthority() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(a1), clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 8111L, a1));
        AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 8111L);
        assertNotNull(request);

        assertEquals(ReturnFlowController.UnlockDispatchValidation.ALLOWED,
                controller.validateUnlockDispatch(
                        generation, 8111L, request, true));
    }

    @Test
    public void finalDispatchGateUsesControllerClockAndRejectsQuietGuardExpiry() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.authorizationLifetimeMillis = 5L;
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 812L, a1));
        AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 812L);
        assertNotNull(request);
        clock.now = 105L;

        assertEquals(ReturnFlowController.UnlockDispatchValidation.AUTHORIZATION_EXPIRED,
                controller.validateUnlockDispatch(
                        generation, 812L, request, true));
        assertEquals(ReturnFlowModel.State.OPENING, controller.snapshot().state());
    }

    @Test
    public void finalDispatchGateRejectsStaleOrMismatchedRequest() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(a1), clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 813L, a1));
        AuthorizedUnlockRequest request =
                controller.beginUnlockDispatch(generation, 813L);
        assertNotNull(request);
        AuthorizedUnlockRequest wrongOperation = new AuthorizedUnlockRequest(
                814L,
                request.target(),
                request.unlockCommand(),
                request.expectedSuccessFrame(),
                request.expectedFailureFrame());

        assertEquals(ReturnFlowController.UnlockDispatchValidation.REJECTED,
                controller.validateUnlockDispatch(
                        generation, 813L, wrongOperation, true));
        assertEquals(ReturnFlowController.UnlockDispatchValidation.REJECTED,
                controller.validateUnlockDispatch(
                        generation - 1L, 813L, request, true));
    }

    @Test
    public void statusBaselineCanBindExactAuthorizationWithoutStartingUnlock() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        ReturnFlowController controller = new ReturnFlowController(
                new FakeService(a1), clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 82L, a1));

        AuthorizedUnlockRequest request =
                controller.authorizedRequestForStatusBaseline(generation, 82L);

        assertNotNull(request);
        assertEquals(ReturnFlowModel.State.CONFIRMING, controller.snapshot().state());
        assertArrayEquals(LockerProtocol.unlockCommand(a1.target()), request.unlockCommand());
    }

    @Test
    public void productionStyleFailureStaysFailClosedAndRetryCanRecoverQuery() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.queryResult = ReturnServiceResult.failure(
                ReturnServiceResult.Code.PERMANENT_FAILURE);
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();

        assertFalse(controller.authenticate(generation, identity("card")));
        assertEquals(ReturnFlowModel.State.LOADING, controller.snapshot().state());
        assertEquals(ReturnFlowController.ErrorCode.SERVICE_UNAVAILABLE,
                controller.snapshot().error());
        assertEquals(0, service.authorizeCalls);

        service.queryResult = ReturnServiceResult.success(
                ReturnLockerList.of(Collections.singletonList(a1)));
        assertTrue(controller.retryQuery(generation));
        assertEquals(ReturnFlowModel.State.SELECTING, controller.snapshot().state());
    }

    @Test
    public void emptyServerListIsTypedNoLockerAndNeverAuthorizes() {
        MutableClock clock = new MutableClock(100L);
        FakeService service = new FakeService();
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();

        assertTrue(controller.authenticate(generation, identity("empty")));

        assertEquals(ReturnFlowModel.State.FAILURE, controller.snapshot().state());
        assertEquals(ReturnFlowController.ErrorCode.NO_LOCKERS,
                controller.snapshot().error());
        assertEquals(0, service.authorizeCalls);
    }

    @Test
    public void authorizationFailureCanRetryButCannotChangeSelectedLocker() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.authorizeResult = ReturnServiceResult.failure(
                ReturnServiceResult.Code.RETRYABLE_FAILURE);
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));

        assertFalse(controller.selectAndAuthorize(generation, 91L, a1));
        assertEquals(ReturnFlowController.ErrorCode.RETRYABLE_FAILURE,
                controller.snapshot().error());
        assertEquals(a1, controller.snapshot().selectedLocker());
        service.authorizeResult = null;

        assertTrue(controller.retryAuthorization(generation, 91L));
        assertNotNull(controller.beginUnlockDispatch(generation, 91L));
        assertEquals(2, service.authorizeCalls);
    }

    @Test
    public void commitFailureRetriesTheExactIssuedAuthorizationIdempotently() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        service.failFirstCompletion = true;
        ReturnFlowController controller = waiting(controller(service, clock), a1, 101L);
        long generation = controller.snapshot().generation();
        assertTrue(controller.acknowledgeDoorClosed(generation, 101L));

        assertTrue(controller.markStableDoorClosed(generation, 101L));
        assertEquals(ReturnFlowModel.State.COMMITTING, controller.snapshot().state());
        assertEquals(ReturnFlowController.ErrorCode.COMMIT_FAILED,
                controller.snapshot().error());
        ReturnAuthorization first = service.completedAuthorizations.get(0);

        assertTrue(controller.retryCommit(generation, 101L));
        assertEquals(ReturnFlowModel.State.SUCCESS, controller.snapshot().state());
        assertEquals(2, service.completeCalls);
        assertTrue(first == service.completedAuthorizations.get(1));
    }

    @Test
    public void statusUncertaintyIsRetainedUntilExplicitResumeAndNeverCommits() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        ReturnFlowController controller = waiting(controller(service, clock), a1, 111L);
        long generation = controller.snapshot().generation();

        assertTrue(controller.markStatusUncertain(generation, 111L));
        assertEquals(ReturnFlowController.ErrorCode.STATUS_UNCERTAIN,
                controller.snapshot().error());
        assertFalse(controller.markStableDoorClosed(generation, 111L));
        assertEquals(0, service.completeCalls);
        assertTrue(controller.resumeStatusMonitoring(generation, 111L));
        assertEquals(ReturnFlowController.ErrorCode.NONE, controller.snapshot().error());
    }

    @Test
    public void unlockFailureRequiresFreshGenerationAndNeverReusesAuthorization() {
        MutableClock clock = new MutableClock(100L);
        ReturnLocker a1 = locker("server-a1", 1);
        FakeService service = new FakeService(a1);
        ReturnFlowController controller = new ReturnFlowController(service, clock);
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("card")));
        assertTrue(controller.selectAndAuthorize(generation, 121L, a1));
        assertNotNull(controller.beginUnlockDispatch(generation, 121L));
        assertTrue(controller.markUnlockFailed(generation, 121L));

        long freshGeneration = controller.restartAfterUnlockFailure(generation, 121L);

        assertTrue(freshGeneration > generation);
        assertEquals(ReturnFlowModel.State.SELECTING, controller.snapshot().state());
        assertFalse(controller.markUnlockAccepted(generation, 121L));
        assertEquals(1, service.authorizeCalls);
    }

    private static ReturnFlowController controller(
            FakeService service, MutableClock clock) {
        return new ReturnFlowController(service, clock);
    }

    private static ReturnFlowController waiting(
            ReturnFlowController controller, ReturnLocker locker, long operationId) {
        long generation = controller.begin();
        assertTrue(controller.authenticate(generation, identity("ready")));
        assertTrue(controller.selectAndAuthorize(generation, operationId, locker));
        assertNotNull(controller.beginUnlockDispatch(generation, operationId));
        assertTrue(controller.markUnlockAccepted(generation, operationId));
        return controller;
    }

    private static ReturnIdentity identity(String credential) {
        return new ReturnIdentity(UnlockMethod.ID_CARD, credential, 90L);
    }

    private static ReturnLocker locker(String id, int localLock) {
        return new ReturnLocker(id, "A" + localLock, "A区", new LockerTarget(
                LockerZone.A, 1, localLock, FeedbackPolarity.SHORT_WHEN_LOCKED));
    }

    private static void assertSnapshotEquals(
            ReturnFlowController.Snapshot expected,
            ReturnFlowController.Snapshot actual) {
        assertEquals(expected.generation(), actual.generation());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.error(), actual.error());
        assertEquals(expected.remainingLockers(), actual.remainingLockers());
        assertEquals(expected.selectedLocker(), actual.selectedLocker());
        assertEquals(expected.operationId(), actual.operationId());
        assertEquals(expected.userAcknowledgedClose(), actual.userAcknowledgedClose());
        assertEquals(expected.stableCloseObserved(), actual.stableCloseObserved());
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

    private static final class FakeService implements ReturnServiceClient {
        final List<ReturnLocker> lockers;
        final List<ReturnAuthorization> completedAuthorizations = new ArrayList<>();
        final List<ReturnAuthorization> issuedAuthorizations = new ArrayList<>();
        ReturnServiceResult<ReturnLockerList> queryResult;
        ReturnServiceResult<ReturnAuthorization> authorizeResult;
        long authorizationLifetimeMillis = 1_000L;
        boolean failFirstCompletion;
        int authorizeCalls;
        int completeCalls;

        FakeService(ReturnLocker... lockers) {
            this.lockers = Arrays.asList(lockers);
        }

        @Override
        public ReturnServiceResult<ReturnLockerList> queryActiveLockers(
                ReturnIdentity identity, long requestId) {
            return queryResult != null ? queryResult
                    : ReturnServiceResult.success(ReturnLockerList.of(lockers));
        }

        @Override
        public ReturnServiceResult<ReturnAuthorization> authorize(
                ReturnIdentity identity, ReturnLocker locker, long operationId) {
            authorizeCalls++;
            if (authorizeResult != null) {
                return authorizeResult;
            }
            ReturnAuthorization authorization = new ReturnAuthorization(
                    operationId,
                    locker,
                    LockerProtocol.unlockCommand(locker.target()),
                    LockerProtocol.successFrame(locker.target()),
                    LockerProtocol.failureFrame(locker.target()),
                    "secret-completion-token-" + operationId + "-issue-" + authorizeCalls,
                    100L + authorizationLifetimeMillis);
            issuedAuthorizations.add(authorization);
            return ReturnServiceResult.success(authorization);
        }

        @Override
        public ReturnServiceResult<ReturnCompletionReceipt> complete(
                ReturnAuthorization authorization) {
            completeCalls++;
            completedAuthorizations.add(authorization);
            if (failFirstCompletion && completeCalls == 1) {
                return ReturnServiceResult.failure(
                        ReturnServiceResult.Code.RETRYABLE_FAILURE);
            }
            return ReturnServiceResult.success(new ReturnCompletionReceipt(
                    authorization.operationId(), completeCalls > 1));
        }
    }
}
