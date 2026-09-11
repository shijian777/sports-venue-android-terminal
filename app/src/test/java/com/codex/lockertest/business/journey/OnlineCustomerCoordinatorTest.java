package com.codex.lockertest.business.journey;

import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.AssignedCabinet;
import com.codex.lockertest.business.BoardAction;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.BusinessResponseParsers;
import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.business.EmptyBusinessResult;
import com.codex.lockertest.business.SessionToken;
import com.codex.lockertest.business.UsedCabinet;
import com.codex.lockertest.business.UsedCabinetList;
import com.codex.lockertest.business.UserInfoRequest;
import com.codex.lockertest.business.UserType;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;
import com.codex.lockertest.unlock.UnlockCoordinator;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OnlineCustomerCoordinatorTest {
    @Test
    public void noEntryRecordShowsActionableReasonWithoutRequestingAnyCabinet() {
        for (OnlineCustomerCoordinator.Purpose purpose : OnlineCustomerCoordinator.Purpose.values()) {
            Harness harness = rejectedIdentityHarness(purpose,
                    "{\"code\":400,\"message\":\"无进场记录\",\"data\":null}");
            assertEquals("NO_ENTRY_RECORD", harness.controller.snapshot().failure().name());
            assertEquals("暂无进场记录，请联系前台确认入场后重试",
                    harness.controller.snapshot().failure().message());
            assertNoCabinetAccess(harness);
        }
    }

    @Test
    public void unknownIdentityRejectionsDoNotMasqueradeAsNetworkFailuresOrExposeMessage() {
        for (int code : new int[] {2, 400, 401, 500}) {
            Harness harness = rejectedIdentityHarness(OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                    "{\"code\":" + code + ",\"message\":\"untrusted-credential-marker\","
                            + "\"data\":{\"token\":\"test-only-token\",\"user_type\":0,"
                            + "\"locker_check_status\":0,\"uid\":1}}");
            assertEquals("REMOTE_REJECTED", harness.controller.snapshot().failure().name());
            assertEquals("服务器未通过本次请求，请核对信息或联系前台",
                    harness.controller.snapshot().failure().message());
            assertFalse(harness.controller.snapshot().toString().contains("untrusted-credential-marker"));
            assertFalse(harness.controller.snapshot().toString().contains("test-only-token"));
            assertNoCabinetAccess(harness);
        }
    }

    @Test
    public void previewRejectionAfterSuccessfulAuthenticationUsesGenericRequestMessage() {
        Harness harness = new Harness(new FakeExecutor());
        harness.service.authResult = ApiResult.success(customer());
        harness.service.previewResult = BusinessResponseParsers.controlPanelPreview(
                "{\"code\":400,\"message\":\"untrusted-query-marker\",\"data\":null}");
        assertTrue(harness.controller.start(session(74L),
                OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("test-only-credential")));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.LOADING_PREVIEW,
                harness.controller.snapshot().state());

        harness.scheduler.runNextWorker();

        assertEquals(1, harness.service.authCalls);
        assertEquals(1, harness.service.previewCalls);
        assertEquals(0, harness.service.usedCalls);
        assertRejectedQueryHasNoPhysicalAccess(harness);
    }

    @Test
    public void usedCabinetRejectionAfterSuccessfulAuthenticationUsesGenericRequestMessage() {
        Harness harness = new Harness(new FakeExecutor());
        harness.service.authResult = ApiResult.success(customer());
        harness.service.usedResult = BusinessResponseParsers.useCabinetList(
                "{\"code\":400,\"message\":\"untrusted-query-marker\",\"data\":null}");
        assertTrue(harness.controller.start(session(75L),
                OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                UserInfoRequest.qr("test-only-credential")));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.LOADING_USED,
                harness.controller.snapshot().state());

        harness.scheduler.runNextWorker();

        assertEquals(1, harness.service.authCalls);
        assertEquals(0, harness.service.previewCalls);
        assertEquals(1, harness.service.usedCalls);
        assertRejectedQueryHasNoPhysicalAccess(harness);
    }

    private static void assertRejectedQueryHasNoPhysicalAccess(Harness harness) {
        OnlineCustomerSnapshot snapshot = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.FAILED, snapshot.state());
        assertEquals(OnlineCustomerSnapshot.Failure.REMOTE_REJECTED, snapshot.failure());
        assertEquals("服务器未通过本次请求，请核对信息或联系前台", snapshot.failure().message());
        assertFalse(snapshot.toString().contains("untrusted-query-marker"));
        assertFalse(snapshot.hasPendingCredential());
        assertFalse(snapshot.physicalOpenEnabled());
        assertFalse(harness.controller.confirmOpen(101L));
        assertEquals(0, harness.scheduler.workerCount());
        assertEquals(0, harness.service.userBoardCalls);
        assertEquals(0, harness.service.openBoardCalls);
        assertEquals(0, harness.executor.executeCalls);
    }

    @Test
    public void actualNetworkFailuresRetainNetworkRecoveryMessage() {
        Harness harness = new Harness();
        assertTrue(harness.controller.start(session(73L),
                OnlineCustomerCoordinator.Purpose.OPEN_CABINET, UserInfoRequest.qr("test-only-qr")));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.Failure.SERVICE_UNAVAILABLE,
                harness.controller.snapshot().failure());
        assertEquals("服务器暂不可用，请检查网络后重试",
                harness.controller.snapshot().failure().message());
        assertNoCabinetAccess(harness);
    }

    @Test
    public void serverHttpAndMalformedResponsesDoNotAskCustomerToFixCredentialsOrWifi() {
        ServerFailure.Kind[] kinds = {ServerFailure.Kind.HTTP, ServerFailure.Kind.CONTRACT};
        String[] expected = {"SERVER_ERROR", "INVALID_SERVER_LAYOUT"};
        for (int i = 0; i < kinds.length; i++) {
            Harness harness = new Harness();
            harness.service.authResult = failure(kinds[i]);
            assertTrue(harness.controller.start(session(73L),
                    OnlineCustomerCoordinator.Purpose.OPEN_CABINET, UserInfoRequest.qr("test-only-qr")));
            harness.scheduler.runNextWorker();
            assertEquals(expected[i], harness.controller.snapshot().failure().name());
            assertFalse(harness.controller.snapshot().failure().message().contains("检查网络"));
            assertFalse(harness.controller.snapshot().failure().message().contains("凭证错误"));
            assertNoCabinetAccess(harness);
        }
    }

    private static Harness rejectedIdentityHarness(
            OnlineCustomerCoordinator.Purpose purpose, String body) {
        Harness harness = new Harness();
        harness.service.authResult = BusinessResponseParsers.userInfo(body);
        assertTrue(harness.controller.start(session(73L), purpose,
                UserInfoRequest.qr("test-only-credential")));
        harness.scheduler.runNextWorker();
        return harness;
    }

    private static void assertNoCabinetAccess(Harness harness) {
        assertEquals(OnlineCustomerSnapshot.State.FAILED, harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        assertFalse(harness.controller.snapshot().physicalOpenEnabled());
        assertFalse(harness.controller.retry());
        assertFalse(harness.controller.confirmOpen(101L));
        assertEquals(0, harness.scheduler.workerCount());
        assertEquals(0, harness.service.previewCalls);
        assertEquals(0, harness.service.usedCalls);
        assertEquals(0, harness.service.userBoardCalls);
        assertEquals(0, harness.service.openBoardCalls);
    }

    @Test
    public void onlineUnlockExecutorContractIsAvailable() throws Exception {
        Class<?> executorType;
        try {
            executorType = Class.forName(
                    "com.codex.lockertest.business.journey.OnlineUnlockExecutor");
        } catch (ClassNotFoundException missing) {
            assertNotNull("OnlineUnlockExecutor must exist", null);
            return;
        }

        assertNotNull(OnlineCustomerCoordinator.class.getConstructor(
                BusinessService.class,
                OnlineCustomerCoordinator.Scheduler.class,
                OnlineCustomerCoordinator.Clock.class,
                OnlineCustomerCoordinator.Listener.class,
                executorType));
        assertNotNull(OnlineCustomerCoordinator.class.getMethod("confirmOpen", long.class));
        assertNotNull(OnlineCustomerSnapshot.class.getMethod("physicalOpenEnabled"));
    }

    @Test
    public void openJourneyAuthenticatesThenLoadsFirstRegionAndDocumentedDefaultPage() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        harness.service.previewResult = ApiResult.success(preview(
                Arrays.asList(1, 2), 11L, layers(row(1, cabinet(101L, "A01", 11L)))));

        assertTrue(harness.controller.start(
                session(7L),
                OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.phone("13800000000", "001234")));
        assertEquals(OnlineCustomerSnapshot.State.AUTHENTICATING,
                harness.controller.snapshot().state());
        assertTrue(harness.controller.snapshot().hasPendingCredential());

        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.LOADING_PREVIEW,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        harness.scheduler.runNextWorker();

        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        assertEquals(11L, ready.selectedRegion().id());
        assertEquals(1, ready.selectedPage());
        assertEquals(Arrays.asList(1, 2), ready.pages());
        assertEquals(1, ready.rows().size());
        assertEquals(1, ready.rows().get(0).layerKey());
        assertEquals("A01", ready.rows().get(0).slots().get(0).cabinetLabel());
        assertEquals(101L, ready.rows().get(0).slots().get(0).fcId());
        assertEquals(1, harness.service.authCalls);
        assertEquals(1, harness.service.previewCalls);
        assertEquals(0, harness.service.usedCalls);
        assertEquals(2, harness.service.lastPreviewType);
    }

    @Test
    public void returnJourneyLoadsOnlyAuthenticatedUsersOccupiedCabinets() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        harness.service.usedResult = ApiResult.success(new UsedCabinetList(
                "Customer", "13800000000", Collections.singletonList(
                new UsedCabinet(91L, 501L, "A01", "2026-09-06 10:00:00", 12, "notice"))));

        assertTrue(harness.controller.start(
                session(8L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                UserInfoRequest.card("card-value")));
        harness.scheduler.runNextWorker();
        harness.scheduler.runNextWorker();

        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_USED, ready.state());
        assertEquals(1, ready.usedCabinets().size());
        assertEquals(91L, ready.usedCabinets().get(0).recordId());
        assertEquals("A01", ready.usedCabinets().get(0).name());
        assertEquals(0, harness.service.previewCalls);
        assertEquals(1, harness.service.usedCalls);
    }

    @Test
    public void authenticatedCustomerStartsReturnQueryWithoutAuthenticatingAgain() {
        Harness harness = new Harness();
        harness.service.usedResult = ApiResult.success(usedCabinets());

        assertTrue(harness.controller.startAuthenticated(
                session(108L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET, customer()));
        assertEquals(OnlineCustomerSnapshot.State.LOADING_USED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        assertFalse(harness.controller.startAuthenticated(
                session(108L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET, customer()));
        assertEquals(0, harness.service.authCalls);
        assertEquals(1, harness.scheduler.workerCount());

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.BROWSING_USED,
                harness.controller.snapshot().state());
        assertEquals(1, harness.service.usedCalls);
        assertEquals(0, harness.service.authCalls);
    }

    @Test
    public void authenticatedEntryRejectsAdministratorWithoutAuthenticationOrCabinetQuery() {
        Harness harness = new Harness();
        AuthenticatedUser administrator = new AuthenticatedUser(
                SessionToken.of("admin-not-customer"), UserType.ADMIN, 0, 18L);

        assertFalse(harness.controller.startAuthenticated(
                session(109L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                administrator));

        assertEquals(OnlineCustomerSnapshot.State.FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.CUSTOMER_IDENTITY_REQUIRED,
                harness.controller.snapshot().failure());
        assertEquals(0, harness.service.authCalls);
        assertEquals(0, harness.service.usedCalls);
        assertEquals(0, harness.scheduler.workerCount());
    }

    @Test
    public void cancellingAuthenticatedEntryInvalidatesItsQueryAndGeneration() {
        Harness harness = new Harness();
        harness.service.usedResult = ApiResult.success(usedCabinets());
        assertTrue(harness.controller.startAuthenticated(
                session(110L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET, customer()));

        harness.controller.cancel();
        harness.scheduler.runAllWorkersIncludingCancelled();

        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        // Cancellation clears the authenticated session, including its bootstrap binding.
        assertEquals(0L, harness.controller.snapshot().bootstrapGeneration());
        assertEquals(0, harness.service.authCalls);
        assertEquals(0, harness.service.usedCalls);

        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, cabinet(701L, "A01", 11L)))));
        assertTrue(harness.controller.startAuthenticated(
                session(111L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET, customer()));
        harness.scheduler.runAllWorkersIncludingCancelled();
        assertEquals(111L, harness.controller.snapshot().bootstrapGeneration());
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN,
                harness.controller.snapshot().state());
        assertEquals(0, harness.service.authCalls);
    }

    @Test
    public void selectedOwnedCabinetReturnsOnceWithoutAllocationOrPhysicalOpen() {
        Harness harness = readyReturnHarness();
        harness.service.openBoardResult = ApiResult.success(EmptyBusinessResult.INSTANCE);

        assertFalse(harness.controller.confirmReturn(0L));
        assertFalse(harness.controller.confirmReturn(999L));
        assertTrue(harness.controller.confirmReturn(501L));
        assertEquals(OnlineCustomerSnapshot.State.RETURNING,
                harness.controller.snapshot().state());
        assertEquals("A01", harness.controller.snapshot().selectedCabinetLabel());
        assertTrue(harness.controller.snapshot().isBusy());
        assertFalse(harness.controller.confirmReturn(501L));
        assertEquals(1, harness.scheduler.workerCount());

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.RETURN_SUCCEEDED,
                harness.controller.snapshot().state());
        assertEquals(1, harness.service.openBoardCalls);
        assertEquals(BoardAction.RETURN, harness.service.lastBoardAction);
        assertEquals(501L, harness.service.lastOpenBoardFcId);
        assertEquals(0, harness.service.userBoardCalls);
        assertEquals(0, harness.executor == null ? 0 : harness.executor.executeCalls);
        assertFalse(harness.controller.snapshot().isBusy());
        assertFalse(harness.controller.confirmReturn(501L));
    }

    @Test
    public void failedReturnRequiresOwnedListRequeryBeforeAnotherMutation() {
        Harness harness = readyReturnHarness();
        harness.service.openBoardResult = failure(ServerFailure.Kind.NETWORK);
        assertTrue(harness.controller.confirmReturn(501L));

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.RETURN_FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.SERVICE_UNAVAILABLE,
                harness.controller.snapshot().failure());
        assertEquals(1, harness.service.openBoardCalls);
        assertFalse(harness.controller.confirmReturn(501L));
        assertEquals(0, harness.scheduler.workerCount());

        assertTrue(harness.controller.retry());
        assertEquals(OnlineCustomerSnapshot.State.LOADING_USED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.confirmReturn(501L));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_USED,
                harness.controller.snapshot().state());
        assertEquals(2, harness.service.usedCalls);

        harness.service.openBoardResult = ApiResult.success(EmptyBusinessResult.INSTANCE);
        assertTrue(harness.controller.confirmReturn(501L));
        assertEquals(1, harness.service.openBoardCalls);
    }

    @Test
    public void returnTimeoutAndLateWorkerCannotMarkCabinetReturned() {
        Harness harness = readyReturnHarness();
        harness.service.openBoardResult = ApiResult.success(EmptyBusinessResult.INSTANCE);
        assertTrue(harness.controller.confirmReturn(501L));
        harness.clock.now += OnlineCustomerCoordinator.REQUEST_TIMEOUT_MILLIS;

        harness.scheduler.runNextLiveTimer();

        assertEquals(OnlineCustomerSnapshot.State.RETURN_FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                harness.controller.snapshot().failure());
        harness.scheduler.runAllWorkersIncludingCancelled();
        assertEquals(0, harness.service.openBoardCalls);
        assertEquals(OnlineCustomerSnapshot.State.RETURN_FAILED,
                harness.controller.snapshot().state());
        assertTrue(harness.controller.retry());
    }

    @Test
    public void cancellationDuringReturnMakesSuccessfulReplyStale() {
        Harness harness = readyReturnHarness();
        harness.service.openBoardResult = ApiResult.success(EmptyBusinessResult.INSTANCE);
        harness.service.openBoardHook = harness.controller::cancel;
        assertTrue(harness.controller.confirmReturn(501L));

        harness.scheduler.runNextWorker();

        assertEquals(1, harness.service.openBoardCalls);
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.confirmReturn(501L));
        assertFalse(harness.controller.retry());
    }

    @Test
    public void adminOrDynamicCodeIdentityIsRejectedWithoutCabinetQuery() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(new AuthenticatedUser(
                SessionToken.of("token-not-exposed"), UserType.ADMIN, 0, 12L));
        assertTrue(harness.controller.start(
                session(9L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("qr-value")));

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.CUSTOMER_IDENTITY_REQUIRED,
                harness.controller.snapshot().failure());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        assertEquals(0, harness.service.previewCalls);
        assertEquals(0, harness.service.usedCalls);
        assertFalse(harness.controller.retry());
    }

    @Test
    public void duplicateScanCannotQueueSecondAuthentication() {
        Harness harness = new Harness();
        assertTrue(harness.controller.start(
                session(10L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("first")));

        assertFalse(harness.controller.start(
                session(10L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.card("second")));
        assertEquals(1, harness.scheduler.workerCount());
        harness.scheduler.runNextWorker();
        assertEquals(1, harness.service.authCalls);
    }

    @Test
    public void cancelImmediatelyClearsCredentialAndLateAuthenticationCannotNavigate() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(11L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("sensitive-value")));
        harness.controller.cancel();

        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        harness.scheduler.runAllWorkersIncludingCancelled();
        assertEquals(0, harness.service.authCalls);
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
    }

    @Test
    public void timeoutFailsImmediatelyAndWedgedWorkerCompletionIsStale() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(12L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.card("sensitive-value")));
        harness.clock.now += OnlineCustomerCoordinator.REQUEST_TIMEOUT_MILLIS;
        harness.scheduler.runNextTimer();

        assertEquals(OnlineCustomerSnapshot.State.FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                harness.controller.snapshot().failure());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        harness.scheduler.runAllWorkersIncludingCancelled();
        assertEquals(0, harness.service.authCalls);
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                harness.controller.snapshot().failure());
    }

    @Test
    public void oldDeviceGenerationCallbackCannotReplaceNewSession() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(20L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("old")));
        harness.controller.cancel();
        assertTrue(harness.controller.start(
                session(21L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                UserInfoRequest.card("new")));

        harness.scheduler.runAllWorkersIncludingCancelled();

        assertEquals(21L, harness.controller.snapshot().bootstrapGeneration());
        assertEquals(OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                harness.controller.snapshot().purpose());
        assertEquals(1, harness.service.authCalls);
    }

    @Test
    public void cancelledOldPreviewCannotUseOrReplaceNewAuthenticatedSession() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(22L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("old")));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.LOADING_PREVIEW,
                harness.controller.snapshot().state());

        harness.controller.cancel();
        assertTrue(harness.controller.start(
                session(23L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET,
                UserInfoRequest.card("new")));
        harness.scheduler.runAllWorkersIncludingCancelled();

        assertEquals(23L, harness.controller.snapshot().bootstrapGeneration());
        assertEquals(0, harness.service.previewCalls);
        assertEquals(1, harness.service.usedCalls);
    }

    @Test
    public void authenticationFailureRequiresFreshCredentialInsteadOfReplayRetry() {
        Harness harness = new Harness();
        harness.service.authResult = failure(ServerFailure.Kind.NETWORK);
        assertTrue(harness.controller.start(
                session(30L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.phone("13800000000", "009900")));
        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.Failure.SERVICE_UNAVAILABLE,
                harness.controller.snapshot().failure());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
        assertFalse(harness.controller.retry());
        assertEquals(0, harness.scheduler.workerCount());

        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(30L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.phone("13800000000", "009901")));
    }

    @Test
    public void failedPreviewCanRetryWithAuthenticatedSessionWithoutReauthentication() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        harness.service.previewResult = failure(ServerFailure.Kind.NETWORK);
        assertTrue(harness.controller.start(
                session(40L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("qr")));
        harness.scheduler.runNextWorker();
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.Failure.SERVICE_UNAVAILABLE,
                harness.controller.snapshot().failure());

        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, cabinet(111L, "A01", 11L)))));
        assertTrue(harness.controller.retry());
        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN,
                harness.controller.snapshot().state());
        assertEquals(1, harness.service.authCalls);
        assertEquals(2, harness.service.previewCalls);
    }

    @Test
    public void regionAndPageChangesUseOnlyBootstrapRegionsAndServerPages() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        harness.service.previewResult = ApiResult.success(preview(
                Arrays.asList(1, 3), 11L, layers(row(1, cabinet(1L, "A01", 11L)))));
        assertTrue(harness.controller.start(
                session(50L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("qr")));
        harness.scheduler.runNextWorker();
        harness.scheduler.runNextWorker();

        assertFalse(harness.controller.changePage(2));
        assertFalse(harness.controller.changeRegion(999L));
        assertTrue(harness.controller.changePage(3));
        harness.service.previewResult = ApiResult.success(preview(
                Arrays.asList(1, 3), 11L, layers(row(1, cabinet(3L, "A03", 11L)))));
        harness.scheduler.runNextWorker();
        assertEquals(3, harness.service.lastPage);

        assertTrue(harness.controller.changeRegion(22L));
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 22L,
                layers(row(1, cabinet(4L, "B01", 22L)))));
        harness.scheduler.runNextWorker();
        assertEquals(22L, harness.service.lastAreaId);
        assertEquals(1, harness.service.lastPage);
    }

    @Test
    public void twelveCabinetsWrapEightAndFourWithoutChangingPhysicalMetadataOrAuthority() {
        Harness harness = new Harness(new FakeExecutor());
        harness.service.authResult = ApiResult.success(customer());
        List<ControlPanelPreview.Cabinet> cabinets = cabinetRange(101L, 11);
        cabinets.add(freeCabinet(987L, "柜012（原名）", 11L, "02", "05", "8A0205119C"));
        harness.service.previewResult = ApiResult.success(preview(
                Arrays.asList(1, 3), 11L, layers(row(7, cabinets))));
        assertTrue(harness.controller.start(session(80L),
                OnlineCustomerCoordinator.Purpose.OPEN_CABINET, UserInfoRequest.qr("test-only")));
        harness.scheduler.runNextWorker();
        harness.scheduler.runNextWorker();

        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        assertEquals("A区", ready.selectedRegion().name());
        assertEquals(Arrays.asList(1, 3), ready.pages());
        assertEquals(1, ready.rows().size());
        assertEquals(7, ready.rows().get(0).layerKey());
        assertEquals(12, ready.rows().get(0).slots().size());
        List<List<OnlineCustomerSnapshot.Slot>> display = ready.displayRows();
        assertEquals(2, display.size());
        assertEquals(8, display.get(0).size());
        assertEquals(4, display.get(1).size());
        assertEquals(101L, display.get(0).get(0).fcId());
        assertEquals(108L, display.get(0).get(7).fcId());
        assertEquals(109L, display.get(1).get(0).fcId());
        OnlineCustomerSnapshot.Slot last = display.get(1).get(3);
        assertEquals(12, last.position());
        assertEquals(987L, last.fcId());
        assertEquals(1987L, last.channelId());
        assertEquals("柜012（原名）", last.cabinetLabel());
        assertEquals(0, last.status());
        assertEquals(11L, last.areaId());
        assertEquals(0, last.checkStatus());
        assertTrue(ready.physicalOpenEnabled());
        assertEquals(0, harness.service.userBoardCalls);
        assertEquals(0, harness.executor.executeCalls);

        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(987L));
        assertTrue(harness.controller.confirmOpen(last.fcId()));
        assertFalse(harness.controller.confirmOpen(last.fcId()));
        harness.scheduler.runNextWorker();
        assertEquals(987L, harness.service.lastUserBoardFcId);
        assertEquals(11L, harness.service.lastUserBoardAreaId);
        assertArrayEquals(bytes(0x8A, 0x02, 0x05, 0x11, 0x9C),
                harness.executor.request.unlockCommand());
        assertEquals(1, harness.executor.executeCalls);
        assertEquals(0, harness.service.openBoardCalls);
    }

    @Test
    public void normalSparsePhysicalRowsKeepTheirExistingDisplayGapsAndServerOrder() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(
                        row(9, cabinetRange(101L, 8)),
                        row(3, Collections.<ControlPanelPreview.Cabinet>emptyList()),
                        row(7, cabinet(987L, "原柜名", 11L)),
                        row(2, cabinetRange(201L, 4)))));
        harness.scheduler.runNextWorker();
        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        List<List<OnlineCustomerSnapshot.Slot>> display = ready.displayRows();
        assertEquals(4, display.size());
        assertEquals(8, display.get(0).size());
        assertTrue(display.get(1).isEmpty());
        assertEquals(987L, display.get(2).get(0).fcId());
        assertEquals(4, display.get(3).size());
        assertEquals(9, ready.rows().get(0).layerKey());
        assertEquals(3, ready.rows().get(1).layerKey());
        assertEquals(7, ready.rows().get(2).layerKey());
        assertEquals(2, ready.rows().get(3).layerKey());
    }

    @Test
    public void thirtyTwoCabinetsFitFourDisplayRowsWithoutInventingServerPages() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(row(7, cabinetRange(101L, 32)))));
        harness.scheduler.runNextWorker();
        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        List<List<OnlineCustomerSnapshot.Slot>> display = ready.displayRows();
        assertEquals(4, display.size());
        for (List<OnlineCustomerSnapshot.Slot> row : display) assertEquals(8, row.size());
        assertEquals(132L, display.get(3).get(7).fcId());
        assertEquals(Collections.singletonList(1), ready.pages());
        assertFalse(harness.controller.changePage(2));
    }

    @Test
    public void morePhysicalRowsCompactOnlyPresentationWithoutDroppingCabinets() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(
                        row(9, cabinetRange(101L, 9)),
                        row(3, cabinetRange(201L, 9)),
                        row(7, cabinetRange(301L, 9)))));
        harness.scheduler.runNextWorker();
        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        List<List<OnlineCustomerSnapshot.Slot>> display = ready.displayRows();
        assertEquals(4, display.size());
        assertEquals(8, display.get(0).size());
        assertEquals(8, display.get(1).size());
        assertEquals(8, display.get(2).size());
        assertEquals(3, display.get(3).size());
        assertEquals(109L, display.get(1).get(0).fcId());
        assertEquals(201L, display.get(1).get(1).fcId());
        assertEquals(309L, display.get(3).get(2).fcId());
        assertEquals(9, ready.rows().get(0).layerKey());
        assertEquals(3, ready.rows().get(1).layerKey());
        assertEquals(7, ready.rows().get(2).layerKey());

        Harness sparse = authenticatedOpenHarness();
        sparse.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(
                        row(9, cabinet(90L, "九", 11L)), row(3, cabinet(30L, "三", 11L)),
                        row(7, cabinet(70L, "七", 11L)), row(2, cabinet(20L, "二", 11L)),
                        row(4, cabinet(40L, "四", 11L)))));
        sparse.scheduler.runNextWorker();
        OnlineCustomerSnapshot sparseReady = sparse.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, sparseReady.state());
        assertEquals(5, sparseReady.rows().size());
        List<List<OnlineCustomerSnapshot.Slot>> sparseDisplay = sparseReady.displayRows();
        assertEquals(1, sparseDisplay.size());
        assertEquals(5, sparseDisplay.get(0).size());
        assertEquals(90L, sparseDisplay.get(0).get(0).fcId());
        assertEquals(40L, sparseDisplay.get(0).get(4).fcId());
    }

    @Test
    public void oversizedPreviewOrWrongPageIsUnavailableInsteadOfTruncatedOrInvented() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(row(1, cabinetRange(1L, 33)))));

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                harness.controller.snapshot().failure());
        assertTrue(harness.controller.snapshot().rows().isEmpty());
        assertTrue(harness.controller.snapshot().displayRows().isEmpty());
        assertTrue(harness.controller.snapshot().pages().isEmpty());
        assertFalse(harness.controller.confirmOpen(1L));
        assertFalse(harness.controller.changePage(2));

        Harness wrongPage = authenticatedOpenHarness();
        wrongPage.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(2), 11L,
                layers(row(1, cabinet(1L, "A01", 11L)))));
        wrongPage.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                wrongPage.controller.snapshot().failure());

        Harness tooManySlots = authenticatedOpenHarness();
        tooManySlots.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(
                        row(9, cabinetRange(101L, 8)), row(3, cabinetRange(201L, 8)),
                        row(7, cabinetRange(301L, 8)), row(2, cabinetRange(401L, 8)),
                        row(4, cabinet(501L, "溢出", 11L)))));
        tooManySlots.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                tooManySlots.controller.snapshot().failure());
    }

    @Test
    public void wrappedPreviewCannotHideDuplicateCabinetOrChannelIdentityPastColumnEight() {
        for (boolean duplicateCabinetId : new boolean[] {true, false}) {
            Harness harness = authenticatedOpenHarness();
            List<ControlPanelPreview.Cabinet> cabinets = cabinetRange(101L, 11);
            cabinets.add(new ControlPanelPreview.Cabinet(
                    duplicateCabinetId ? 101L : 987L,
                    duplicateCabinetId ? 1987L : 1101L,
                    "末尾重复柜格", 0, 11L, "02", "05", "8A0205119C", 0));
            harness.service.previewResult = ApiResult.success(preview(
                    Collections.singletonList(1), 11L, layers(row(7, cabinets))));
            harness.scheduler.runNextWorker();
            assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                    harness.controller.snapshot().failure());
            assertTrue(harness.controller.snapshot().rows().isEmpty());
            assertTrue(harness.controller.snapshot().displayRows().isEmpty());
            assertTrue(harness.controller.snapshot().pages().isEmpty());
            assertFalse(harness.controller.confirmOpen(101L));
            assertEquals(0, harness.service.userBoardCalls);
        }
    }

    @Test
    public void wrappedCabinetCannotBeConfirmedAfterPageOrRegionNavigation() {
        for (boolean changeRegion : new boolean[] {false, true}) {
            Harness harness = new Harness(new FakeExecutor());
            harness.service.authResult = ApiResult.success(customer());
            List<ControlPanelPreview.Cabinet> cabinets = cabinetRange(101L, 11);
            cabinets.add(freeCabinet(987L, "原末尾柜", 11L, "02", "05", "8A0205119C"));
            harness.service.previewResult = ApiResult.success(preview(
                    Arrays.asList(1, 3), 11L, layers(row(7, cabinets))));
            assertTrue(harness.controller.start(session(80L),
                    OnlineCustomerCoordinator.Purpose.OPEN_CABINET, UserInfoRequest.qr("test-only")));
            harness.scheduler.runNextWorker();
            harness.scheduler.runNextWorker();
            OnlineCustomerSnapshot oldSnapshot = harness.controller.snapshot();
            assertEquals(987L, oldSnapshot.displayRows().get(1).get(3).fcId());

            assertTrue(changeRegion
                    ? harness.controller.changeRegion(22L) : harness.controller.changePage(3));
            assertFalse(harness.controller.confirmOpen(987L));
            assertTrue(harness.controller.snapshot().displayRows().isEmpty());
            long areaId = changeRegion ? 22L : 11L;
            harness.service.previewResult = ApiResult.success(preview(
                    Arrays.asList(1, 3), areaId, layers(row(2,
                            freeCabinet(999L, "新页柜", areaId, "01", "01", "8A0101119B")))));
            harness.scheduler.runNextWorker();
            assertFalse(harness.controller.confirmOpen(987L));
            assertEquals(987L, oldSnapshot.displayRows().get(1).get(3).fcId());
            assertEquals(999L, harness.controller.snapshot().displayRows().get(0).get(0).fcId());
            assertEquals(0, harness.service.userBoardCalls);
            assertEquals(0, harness.executor.executeCalls);

            harness.controller.cancel();
            assertFalse(harness.controller.confirmOpen(999L));
            assertTrue(harness.controller.snapshot().rows().isEmpty());
            assertTrue(harness.controller.snapshot().displayRows().isEmpty());
        }
    }

    @Test
    public void existingFullFourByEightPreviewKeepsEveryPhysicalRowUnchanged() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L, layers(
                        row(9, cabinetRange(101L, 8)), row(3, cabinetRange(201L, 8)),
                        row(7, cabinetRange(301L, 8)), row(2, cabinetRange(401L, 8)))));
        harness.scheduler.runNextWorker();
        OnlineCustomerSnapshot ready = harness.controller.snapshot();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN, ready.state());
        assertEquals(4, ready.displayRows().size());
        for (int index = 0; index < 4; index++) {
            assertEquals(8, ready.displayRows().get(index).size());
            assertEquals(ready.rows().get(index).slots(), ready.displayRows().get(index));
        }
        assertEquals(101L, ready.displayRows().get(0).get(0).fcId());
        assertEquals(201L, ready.displayRows().get(1).get(0).fcId());
        assertEquals(301L, ready.displayRows().get(2).get(0).fcId());
        assertEquals(401L, ready.displayRows().get(3).get(0).fcId());
    }

    @Test
    public void previewFromWrongAreaIsRejectedAndCommandsAreNeverExposed() {
        Harness harness = authenticatedOpenHarness();
        harness.service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, cabinet(1L, "B01", 22L)))));

        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                harness.controller.snapshot().failure());
        assertTrue(harness.controller.snapshot().rows().isEmpty());
        assertFalse(harness.controller.snapshot().toString().contains("open-command"));
    }

    @Test
    public void physicalActionIsAlwaysExplicitlyUnavailableAndCallsNoMutation() {
        Harness harness = new Harness();

        assertFalse(harness.controller.requestPhysicalAction());
        assertFalse(harness.controller.confirmOpen(1L));
        assertFalse(harness.controller.snapshot().physicalOpenEnabled());
        assertTrue(harness.controller.snapshot().physicalActionUnavailableReason()
                .contains("仅可查看"));
        assertEquals(0, harness.service.userBoardCalls);
        assertEquals(0, harness.service.openBoardCalls);
    }

    @Test
    public void allocationBusinessRejectionDisplaysServerReasonWithoutOpeningOrRetainingSession() {
        Harness harness = readyOpenHarness(new FakeExecutor(),
                freeCabinet(101L, "A01", 11L, "01", "02", "8A01021198"));
        harness.service.userBoardResult = BusinessResponseParsers.userBoard(
                "{\"code\":400,\"message\":\"您无法使用该区域柜子\"}");
        assertTrue(harness.controller.confirmOpen(101L));
        harness.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED, harness.controller.snapshot().state());
        assertEquals("您无法使用该区域柜子", harness.controller.snapshot().physicalDetail());
        assertFalse(harness.controller.snapshot().toString().contains("您无法使用该区域柜子"));
        assertEquals(0, harness.executor.executeCalls);
        assertEquals(1, harness.service.userBoardCalls);
        assertFalse(harness.controller.confirmOpen(101L));
        assertFalse(harness.controller.retry());

        assertTrue(harness.controller.start(session(99L),
                OnlineCustomerCoordinator.Purpose.OPEN_CABINET, UserInfoRequest.qr("fresh-user")));
        assertFalse(harness.controller.snapshot().physicalDetail().contains("您无法使用该区域柜子"));
    }

    @Test
    public void unsafeOrNonBusinessAllocationResponsesNeverBecomeCustomerText() {
        String longMessage = new String(new char[513]).replace('\0', 'x');
        String[] responses = {
                "{\"code\":400,\"message\":\"\"}",
                "{\"code\":400,\"message\":\" \"}",
                "{\"code\":400,\"message\":\"unsafe\\nmessage\"}",
                "{\"code\":400,\"message\":\"<html>unsafe</html>\"}",
                "{\"code\":400,\"message\":\"unsafe\",\"trace\":\"private\"}",
                "{\"code\":400,\"message\":\"" + longMessage + "\"}",
                "{\"code\":500,\"message\":\"unsafe\"}",
                "<html>unsafe</html>"
        };
        for (String response : responses) {
            Harness harness = readyOpenHarness(new FakeExecutor(),
                    freeCabinet(101L, "A01", 11L, "01", "02", "8A01021198"));
            harness.service.userBoardResult = BusinessResponseParsers.userBoard(response);
            assertTrue(harness.controller.confirmOpen(101L));
            harness.scheduler.runNextWorker();
            assertFalse(harness.controller.snapshot().physicalDetail().contains("unsafe"));
            assertFalse(harness.controller.snapshot().physicalDetail().contains("private"));
            assertFalse(harness.controller.snapshot().physicalDetail().contains(longMessage));
            assertEquals(0, harness.executor.executeCalls);
        }
    }

    @Test
    public void lateBusinessRejectionCannotReplaceCancelledPage() {
        Harness harness = readyOpenHarness(new FakeExecutor(),
                freeCabinet(101L, "A01", 11L, "01", "02", "8A01021198"));
        harness.service.userBoardResult = BusinessResponseParsers.userBoard(
                "{\"code\":400,\"message\":\"您无法使用该区域柜子\",\"data\":null}");
        harness.service.userBoardHook = () -> harness.controller.cancel();
        assertTrue(harness.controller.confirmOpen(101L));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED, harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().physicalDetail().contains("您无法使用该区域柜子"));
        assertEquals(0, harness.executor.executeCalls);
    }

    @Test
    public void matchingAllocationExecutesSelectedUnchangedCommandExactlyOnce() {
        Harness harness = readyOpenHarness(new FakeExecutor(),
                freeCabinet(101L, "A01", 11L, "01", "02", "8A01021198"));
        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(101L));

        assertTrue(harness.controller.snapshot().physicalOpenEnabled());
        assertTrue(harness.controller.confirmOpen(101L));
        assertEquals(OnlineCustomerSnapshot.State.AUTHORIZING_OPEN,
                harness.controller.snapshot().state());
        assertTrue(harness.controller.snapshot().isBusy());
        assertFalse(harness.controller.confirmOpen(101L));
        assertFalse(harness.controller.changePage(2));

        harness.scheduler.runNextWorker();

        assertEquals(1, harness.service.userBoardCalls);
        assertEquals(11L, harness.service.lastUserBoardAreaId);
        assertEquals(101L, harness.service.lastUserBoardFcId);
        assertEquals(1, harness.executor.executeCalls);
        assertArrayEquals(bytes(0x8A, 0x01, 0x02, 0x11, 0x98),
                harness.executor.request.unlockCommand());
        assertEquals(OnlineCustomerSnapshot.State.OPENING,
                harness.controller.snapshot().state());
        assertTrue(harness.controller.snapshot().isBusy());
        assertTrue(harness.executor.registrationAccepted);
        assertFalse(harness.executor.handle.cancelled);
        assertTrue(harness.executor.authorityIsCurrent());
        assertEquals("A01", harness.controller.snapshot().selectedCabinetLabel());
        harness.executor.complete(UnlockCoordinator.State.CONNECTING, "B-zone secret");
        assertEquals(UnlockCoordinator.State.CONNECTING,
                harness.controller.snapshot().physicalState());
        assertFalse(harness.controller.snapshot().physicalDetail().contains("B-zone secret"));
        assertEquals(0, harness.service.openBoardCalls);

        harness.executor.complete(UnlockCoordinator.State.SUCCESS, "secret-command-detail");

        assertEquals(OnlineCustomerSnapshot.State.OPEN_SUCCESS,
                harness.controller.snapshot().state());
        assertEquals(UnlockCoordinator.State.SUCCESS,
                harness.controller.snapshot().physicalState());
        assertEquals("A01", harness.controller.snapshot().selectedCabinetLabel());
        assertFalse(harness.controller.snapshot().isBusy());
        assertFalse(harness.controller.snapshot().physicalDetail()
                .contains("secret-command-detail"));
        assertEquals(1, harness.executor.executeCalls);
        assertEquals(0, harness.service.openBoardCalls);
    }

    @Test
    public void rejectedOrMismatchedAllocationRequiresFreshAuthentication() {
        Harness rejected = readyOpenHarness(new FakeExecutor(),
                freeCabinet(102L, "A02", 11L, "01", "03", "8A01031199"));
        rejected.service.userBoardResult = failure(ServerFailure.Kind.NETWORK);
        assertTrue(rejected.controller.confirmOpen(102L));
        rejected.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                rejected.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.OPEN_AUTHORIZATION_FAILED,
                rejected.controller.snapshot().failure());
        assertEquals(0, rejected.executor.executeCalls);
        assertFalse(rejected.controller.retry());
        assertFalse(rejected.controller.changeRegion(22L));
        assertFalse(rejected.controller.confirmOpen(102L));

        Harness mismatch = readyOpenHarness(new FakeExecutor(),
                freeCabinet(103L, "A03", 11L, "01", "04", "8A0104119E"));
        mismatch.service.userBoardResult = ApiResult.success(new AssignedCabinet(999L));
        assertTrue(mismatch.controller.confirmOpen(103L));
        mismatch.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                mismatch.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                mismatch.controller.snapshot().failure());
        assertEquals(0, mismatch.executor.executeCalls);
        assertEquals(0, mismatch.service.openBoardCalls);
    }

    @Test
    public void malformedOrUnavailableSelectionNeverCallsAllocationOrPhysicalExecutor() {
        FakeExecutor unavailable = new FakeExecutor();
        unavailable.available = false;
        Harness offline = readyOpenHarness(unavailable,
                freeCabinet(104L, "A04", 11L, "01", "05", "8A0105119F"));
        assertFalse(offline.controller.snapshot().physicalOpenEnabled());
        assertFalse(offline.controller.confirmOpen(104L));
        assertEquals(0, offline.service.userBoardCalls);
        assertEquals(0, unavailable.executeCalls);

        FakeExecutor throwingAvailability = new FakeExecutor();
        throwingAvailability.throwOnAvailability = true;
        Harness brokenAvailability = readyOpenHarness(throwingAvailability,
                freeCabinet(105L, "A05", 11L, "01", "06", "8A0106119C"));
        assertFalse(brokenAvailability.controller.confirmOpen(105L));
        assertEquals(0, brokenAvailability.service.userBoardCalls);

        Harness malformed = readyOpenHarness(new FakeExecutor(),
                freeCabinet(106L, "A06", 11L, "01", "06", "not-a-frame"));
        assertFalse(malformed.controller.confirmOpen(106L));
        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                malformed.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.CONFIGURATION,
                malformed.controller.snapshot().failure());
        assertEquals(0, malformed.service.userBoardCalls);
        assertEquals(0, malformed.executor.executeCalls);

        Harness occupied = readyOpenHarness(new FakeExecutor(),
                cabinet(107L, "A07", 11L));
        assertFalse(occupied.controller.confirmOpen(107L));
        assertFalse(occupied.controller.confirmOpen(999L));
        assertEquals(0, occupied.service.userBoardCalls);
    }

    @Test
    public void cancelOrLateAllocationInvalidatesAuthorizationAndSendsNothing() {
        Harness cancelled = readyOpenHarness(new FakeExecutor(),
                freeCabinet(108L, "A08", 11L, "01", "07", "8A0107119D"));
        cancelled.service.userBoardResult = ApiResult.success(new AssignedCabinet(108L));
        assertTrue(cancelled.controller.confirmOpen(108L));
        cancelled.controller.cancel();
        cancelled.scheduler.runAllWorkersIncludingCancelled();

        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                cancelled.controller.snapshot().state());
        assertEquals(0, cancelled.service.userBoardCalls);
        assertEquals(0, cancelled.executor.executeCalls);

        Harness late = readyOpenHarness(new FakeExecutor(),
                freeCabinet(109L, "A09", 11L, "01", "08", "8A01081192"));
        late.service.userBoardResult = ApiResult.success(new AssignedCabinet(109L));
        late.service.userBoardHook = () -> {
            late.clock.now += OnlineCustomerCoordinator.REQUEST_TIMEOUT_MILLIS;
            late.scheduler.runNextLiveTimer();
        };
        assertTrue(late.controller.confirmOpen(109L));
        late.scheduler.runNextWorker();

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                late.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                late.controller.snapshot().failure());
        assertEquals(1, late.service.userBoardCalls);
        assertEquals(0, late.executor.executeCalls);
    }

    @Test
    public void physicalFailureTimeoutAndLateCallbacksCannotRewriteTerminalState() {
        Harness failure = readyOpenHarness(new FakeExecutor(),
                freeCabinet(110L, "A10", 11L, "01", "09", "8A01091193"));
        failure.service.userBoardResult = ApiResult.success(new AssignedCabinet(110L));
        assertTrue(failure.controller.confirmOpen(110L));
        failure.scheduler.runNextWorker();
        failure.executor.complete(UnlockCoordinator.State.FAILURE, "device secret");
        failure.executor.complete(UnlockCoordinator.State.SUCCESS, "late");

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                failure.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.PHYSICAL_OPEN_FAILED,
                failure.controller.snapshot().failure());
        assertFalse(failure.controller.snapshot().physicalDetail().contains("device secret"));

        Harness timeout = readyOpenHarness(new FakeExecutor(),
                freeCabinet(111L, "A11", 11L, "01", "0A", "8A010A1190"));
        timeout.service.userBoardResult = ApiResult.success(new AssignedCabinet(111L));
        assertTrue(timeout.controller.confirmOpen(111L));
        timeout.scheduler.runNextWorker();
        timeout.clock.now += OnlineCustomerCoordinator.REQUEST_TIMEOUT_MILLIS;
        assertFalse(timeout.executor.authorityIsCurrent());
        timeout.scheduler.runNextLiveTimer();

        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                timeout.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                timeout.controller.snapshot().failure());
        assertTrue(timeout.executor.handle.cancelled);
        timeout.executor.complete(UnlockCoordinator.State.SUCCESS, "late");
        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                timeout.controller.snapshot().state());
    }

    @Test
    public void cancelAfterPhysicalSchedulingCancelsHandleAndIgnoresResult() {
        FakeExecutor executor = new FakeExecutor();
        executor.probeCoordinatorLockOnCancel = true;
        Harness harness = readyOpenHarness(executor,
                freeCabinet(112L, "A12", 11L, "01", "0B", "8A010B1191"));
        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(112L));
        assertTrue(harness.controller.confirmOpen(112L));
        harness.scheduler.runNextWorker();

        harness.controller.cancel();

        assertTrue(harness.executor.handle.cancelled);
        assertTrue("physical cancellation must run outside the coordinator lock",
                harness.executor.handle.cancellationObservedCoordinatorUnlocked);
        assertFalse(harness.executor.authorityIsCurrent());
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        harness.executor.complete(UnlockCoordinator.State.SUCCESS, "late");
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
    }

    @Test
    public void cancellationBeforePhysicalRegistrationRejectsAndCancelsPermit() {
        FakeExecutor executor = new FakeExecutor();
        Harness harness = readyOpenHarness(executor,
                freeCabinet(115L, "A15", 11L, "01", "01", "8A0101119B"));
        executor.beforeRegistration = harness.controller::cancel;
        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(115L));
        assertTrue(harness.controller.confirmOpen(115L));

        harness.scheduler.runNextWorker();

        assertFalse(executor.registrationAccepted);
        assertTrue(executor.handle.cancelled);
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        executor.complete(UnlockCoordinator.State.SUCCESS, "late");
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
    }

    @Test
    public void concurrentCancelWaitsForInProgressPhysicalCancellationDrain()
            throws Exception {
        FakeExecutor executor = new FakeExecutor();
        executor.blockCancellation = true;
        Harness harness = readyOpenHarness(executor,
                freeCabinet(117L, "A17", 11L, "01", "03", "8A01031199"));
        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(117L));
        assertTrue(harness.controller.confirmOpen(117L));
        harness.scheduler.runNextWorker();

        Thread firstCancel = new Thread(harness.controller::cancel, "first-cancel");
        firstCancel.setDaemon(true);
        firstCancel.start();
        assertTrue(executor.handle.cancellationStarted.await(2L, TimeUnit.SECONDS));

        CountDownLatch secondReturned = new CountDownLatch(1);
        Thread secondCancel = new Thread(() -> {
            harness.controller.cancel();
            secondReturned.countDown();
        }, "second-cancel");
        secondCancel.setDaemon(true);
        secondCancel.start();

        boolean returnedBeforePhysicalCancellationCompleted;
        try {
            returnedBeforePhysicalCancellationCompleted =
                    secondReturned.await(250L, TimeUnit.MILLISECONDS);
        } finally {
            executor.handle.allowCancellation.countDown();
        }
        assertTrue(secondReturned.await(2L, TimeUnit.SECONDS));
        firstCancel.join(2_000L);
        secondCancel.join(2_000L);
        assertFalse("cancel must wait for an in-progress physical drain",
                returnedBeforePhysicalCancellationCompleted);
    }

    @Test
    public void concurrentCancelCannotDeliverBeforeCommittedPhysicalSuccess()
            throws Exception {
        MutableClock clock = new MutableClock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeService service = new FakeService();
        FakeExecutor executor = new FakeExecutor();
        CountDownLatch successListenerEntered = new CountDownLatch(1);
        CountDownLatch allowSuccessListener = new CountDownLatch(1);
        CountDownLatch cancelDelivered = new CountDownLatch(1);
        List<OnlineCustomerSnapshot.State> delivered =
                Collections.synchronizedList(new ArrayList<>());
        OnlineCustomerCoordinator controller = new OnlineCustomerCoordinator(
                service, scheduler, clock, snapshot -> {
                    if (snapshot.state() == OnlineCustomerSnapshot.State.OPEN_SUCCESS) {
                        successListenerEntered.countDown();
                        try {
                            allowSuccessListener.await(2L, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    delivered.add(snapshot.state());
                    if (snapshot.state() == OnlineCustomerSnapshot.State.CANCELLED) {
                        cancelDelivered.countDown();
                    }
                }, executor);
        service.authResult = ApiResult.success(customer());
        service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, freeCabinet(
                        118L, "A18", 11L, "01", "04", "8A0104119E")))));
        assertTrue(controller.start(
                session(82L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("fresh-auth")));
        scheduler.runNextWorker();
        scheduler.runNextWorker();
        service.userBoardResult = ApiResult.success(new AssignedCabinet(118L));
        assertTrue(controller.confirmOpen(118L));
        scheduler.runNextWorker();
        delivered.clear();

        Thread success = new Thread(
                () -> executor.complete(UnlockCoordinator.State.SUCCESS, "success"),
                "physical-success");
        success.setDaemon(true);
        success.start();
        assertTrue(successListenerEntered.await(2L, TimeUnit.SECONDS));
        assertEquals(OnlineCustomerSnapshot.State.OPEN_SUCCESS,
                controller.snapshot().state());

        Thread cancel = new Thread(controller::cancel, "cancel-after-success-commit");
        cancel.setDaemon(true);
        cancel.start();
        boolean cancelDeliveredBeforeSuccessListenerReturned;
        try {
            cancelDeliveredBeforeSuccessListenerReturned =
                    cancelDelivered.await(250L, TimeUnit.MILLISECONDS);
        } finally {
            allowSuccessListener.countDown();
        }
        assertTrue(cancelDelivered.await(2L, TimeUnit.SECONDS));
        success.join(2_000L);
        cancel.join(2_000L);

        assertFalse("cancellation must not overtake an authoritative success delivery",
                cancelDeliveredBeforeSuccessListenerReturned);
        assertEquals(Arrays.asList(
                OnlineCustomerSnapshot.State.OPEN_SUCCESS,
                OnlineCustomerSnapshot.State.CANCELLED), delivered);
    }

    @Test
    public void reentrantCancelDeliveryWaitsForCurrentListenerToReturn() {
        MutableClock clock = new MutableClock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeService service = new FakeService();
        FakeExecutor executor = new FakeExecutor();
        AtomicReference<OnlineCustomerCoordinator> controllerReference =
                new AtomicReference<>();
        List<String> delivered = new ArrayList<>();
        OnlineCustomerCoordinator controller = new OnlineCustomerCoordinator(
                service, scheduler, clock, snapshot -> {
                    if (snapshot.state() == OnlineCustomerSnapshot.State.OPEN_SUCCESS) {
                        delivered.add("success-enter");
                        controllerReference.get().cancel();
                        delivered.add("success-exit");
                    } else if (snapshot.state() == OnlineCustomerSnapshot.State.CANCELLED) {
                        delivered.add("cancel");
                    }
                }, executor);
        controllerReference.set(controller);
        service.authResult = ApiResult.success(customer());
        service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, freeCabinet(
                        119L, "A19", 11L, "01", "05", "8A0105119F")))));
        assertTrue(controller.start(
                session(83L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("fresh-auth")));
        scheduler.runNextWorker();
        scheduler.runNextWorker();
        service.userBoardResult = ApiResult.success(new AssignedCabinet(119L));
        assertTrue(controller.confirmOpen(119L));
        scheduler.runNextWorker();

        executor.complete(UnlockCoordinator.State.SUCCESS, "success");

        assertEquals(Arrays.asList("success-enter", "success-exit", "cancel"), delivered);
        assertEquals(OnlineCustomerSnapshot.State.CANCELLED, controller.snapshot().state());
        assertTrue(executor.handle.cancelled);
    }

    @Test
    public void listenerCanWaitForConcurrentCancelReturnWithoutDeadlock()
            throws Exception {
        MutableClock clock = new MutableClock();
        ManualScheduler scheduler = new ManualScheduler();
        FakeService service = new FakeService();
        FakeExecutor executor = new FakeExecutor();
        AtomicReference<OnlineCustomerCoordinator> controllerReference =
                new AtomicReference<>();
        AtomicReference<Thread> cancelThreadReference = new AtomicReference<>();
        AtomicBoolean cancelReturnedWhileListenerWaited = new AtomicBoolean();
        CountDownLatch cancelReturned = new CountDownLatch(1);
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        OnlineCustomerCoordinator controller = new OnlineCustomerCoordinator(
                service, scheduler, clock, snapshot -> {
                    if (snapshot.state() == OnlineCustomerSnapshot.State.OPEN_SUCCESS) {
                        delivered.add("success-enter");
                        Thread cancel = new Thread(() -> {
                            controllerReference.get().cancel();
                            cancelReturned.countDown();
                        }, "cancel-awaited-by-listener");
                        cancel.setDaemon(true);
                        cancelThreadReference.set(cancel);
                        cancel.start();
                        try {
                            cancelReturnedWhileListenerWaited.set(
                                    cancelReturned.await(1L, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        delivered.add("success-exit");
                    } else if (snapshot.state() == OnlineCustomerSnapshot.State.CANCELLED) {
                        delivered.add("cancel");
                    }
                }, executor);
        controllerReference.set(controller);
        service.authResult = ApiResult.success(customer());
        service.previewResult = ApiResult.success(preview(
                Collections.singletonList(1), 11L,
                layers(row(1, freeCabinet(
                        120L, "A20", 11L, "01", "05", "8A0105119F")))));
        assertTrue(controller.start(
                session(84L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("fresh-auth")));
        scheduler.runNextWorker();
        scheduler.runNextWorker();
        service.userBoardResult = ApiResult.success(new AssignedCabinet(120L));
        assertTrue(controller.confirmOpen(120L));
        scheduler.runNextWorker();

        executor.complete(UnlockCoordinator.State.SUCCESS, "success");

        Thread cancel = cancelThreadReference.get();
        assertNotNull(cancel);
        cancel.join(2_000L);
        assertFalse("cancel must return while the listener is still waiting", cancel.isAlive());
        assertTrue("listener must observe cancel return without a monitor deadlock",
                cancelReturnedWhileListenerWaited.get());
        assertEquals(Arrays.asList("success-enter", "success-exit", "cancel"), delivered);
        assertTrue(executor.handle.cancelled);
    }

    @Test
    public void expiredPhysicalRegistrationIsRejectedBeforeWriterCanStart() {
        FakeExecutor executor = new FakeExecutor();
        Harness harness = readyOpenHarness(executor,
                freeCabinet(116L, "A16", 11L, "01", "02", "8A01021198"));
        executor.beforeRegistration = () ->
                harness.clock.now += OnlineCustomerCoordinator.REQUEST_TIMEOUT_MILLIS;
        harness.service.userBoardResult = ApiResult.success(new AssignedCabinet(116L));
        assertTrue(harness.controller.confirmOpen(116L));

        harness.scheduler.runNextWorker();

        assertFalse(executor.registrationAccepted);
        assertTrue(executor.handle.cancelled);
        assertFalse(executor.authorityIsCurrent());
        harness.scheduler.runNextLiveTimer();
        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.TIMEOUT,
                harness.controller.snapshot().failure());
    }

    @Test
    public void throwingOrNullPhysicalExecutorHandleFailsClosed() {
        FakeExecutor throwing = new FakeExecutor();
        throwing.throwOnExecute = true;
        Harness throwHarness = readyOpenHarness(throwing,
                freeCabinet(113L, "A13", 11L, "01", "0C", "8A010C1196"));
        throwHarness.service.userBoardResult = ApiResult.success(new AssignedCabinet(113L));
        assertTrue(throwHarness.controller.confirmOpen(113L));
        throwHarness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                throwHarness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.PHYSICAL_OPEN_FAILED,
                throwHarness.controller.snapshot().failure());

        FakeExecutor nullHandle = new FakeExecutor();
        nullHandle.returnNullHandle = true;
        Harness nullHarness = readyOpenHarness(nullHandle,
                freeCabinet(114L, "A14", 11L, "01", "01", "8A0101119B"));
        nullHarness.service.userBoardResult = ApiResult.success(new AssignedCabinet(114L));
        assertTrue(nullHarness.controller.confirmOpen(114L));
        nullHarness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.OPEN_FAILED,
                nullHarness.controller.snapshot().state());
        assertEquals(0, nullHarness.service.openBoardCalls);
    }

    @Test
    public void schedulerRejectionFailsClosedAndDoesNotRetainCredential() {
        Harness harness = new Harness();
        harness.scheduler.rejectNextSubmit = true;

        assertFalse(harness.controller.start(
                session(60L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("sensitive")));

        assertEquals(OnlineCustomerSnapshot.State.FAILED,
                harness.controller.snapshot().state());
        assertEquals(OnlineCustomerSnapshot.Failure.CONFIGURATION,
                harness.controller.snapshot().failure());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
    }

    @Test
    public void closeIsNoThrowAndPermanentlyRejectsNewWork() {
        Harness harness = new Harness();
        assertTrue(harness.controller.start(
                session(70L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("sensitive")));

        harness.controller.close();
        harness.controller.close();

        assertEquals(OnlineCustomerSnapshot.State.CLOSED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.start(
                session(71L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("new")));
    }

    @Test
    public void cancellationHandleExceptionsCannotPreventImmediateInvalidation() {
        Harness harness = new Harness();
        assertTrue(harness.controller.start(
                session(72L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("sensitive")));
        harness.scheduler.poisonCancellations();

        harness.controller.cancel();

        assertEquals(OnlineCustomerSnapshot.State.CANCELLED,
                harness.controller.snapshot().state());
        assertFalse(harness.controller.snapshot().hasPendingCredential());
    }

    private static Harness authenticatedOpenHarness() {
        Harness harness = new Harness();
        harness.service.authResult = ApiResult.success(customer());
        assertTrue(harness.controller.start(
                session(80L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("qr")));
        harness.scheduler.runNextWorker();
        return harness;
    }

    private static List<ControlPanelPreview.Cabinet> cabinetRange(long firstId, int count) {
        List<ControlPanelPreview.Cabinet> cabinets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            cabinets.add(cabinet(firstId + index, "服务器原名-" + (firstId + index), 11L));
        }
        return cabinets;
    }

    private static Harness readyOpenHarness(
            FakeExecutor executor, ControlPanelPreview.Cabinet cabinet) {
        Harness harness = new Harness(executor);
        harness.service.authResult = ApiResult.success(customer());
        harness.service.previewResult = ApiResult.success(preview(
                Arrays.asList(1, 2), cabinet.areaId(), layers(row(1, cabinet))));
        assertTrue(harness.controller.start(
                session(81L), OnlineCustomerCoordinator.Purpose.OPEN_CABINET,
                UserInfoRequest.qr("fresh-auth")));
        harness.scheduler.runNextWorker();
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_OPEN,
                harness.controller.snapshot().state());
        return harness;
    }

    private static Harness readyReturnHarness() {
        Harness harness = new Harness(new FakeExecutor());
        harness.service.usedResult = ApiResult.success(usedCabinets());
        assertTrue(harness.controller.startAuthenticated(
                session(181L), OnlineCustomerCoordinator.Purpose.RETURN_CABINET, customer()));
        harness.scheduler.runNextWorker();
        assertEquals(OnlineCustomerSnapshot.State.BROWSING_USED,
                harness.controller.snapshot().state());
        return harness;
    }

    private static UsedCabinetList usedCabinets() {
        return new UsedCabinetList(
                "Customer", "13800000000", Arrays.asList(
                new UsedCabinet(91L, 501L, "A01", "2026-09-09 10:00:00", 12, "notice"),
                new UsedCabinet(92L, 502L, "A02", "2026-09-09 10:05:00", 7, "")));
    }

    private static OnlineCustomerSession session(long generation) {
        return new OnlineCustomerSession(
                generation,
                "merchant",
                "device",
                Arrays.asList(
                        new OnlineCustomerSession.Region(11L, "A区"),
                        new OnlineCustomerSession.Region(22L, "B区")));
    }

    private static AuthenticatedUser customer() {
        return new AuthenticatedUser(
                SessionToken.of("secret-session-token"), UserType.USER, 0, 81L);
    }

    private static ControlPanelPreview preview(
            List<Integer> pages,
            long areaId,
            Map<Integer, List<ControlPanelPreview.Cabinet>> layers) {
        return new ControlPanelPreview(
                pages,
                Collections.singletonList(new ControlPanelPreview.AllOpenCommand(
                        "A1", "all-open-command")),
                layers);
    }

    private static ControlPanelPreview.Cabinet cabinet(
            long id, String label, long areaId) {
        return new ControlPanelPreview.Cabinet(
                id, id + 1000L, label, 1, areaId,
                "A1", "01", "open-command", 1);
    }

    private static ControlPanelPreview.Cabinet freeCabinet(
            long id, String label, long areaId,
            String boardHex, String channelNo, String openCommand) {
        return new ControlPanelPreview.Cabinet(
                id, id + 1000L, label, 0, areaId,
                boardHex, channelNo, openCommand, 0);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    @SafeVarargs
    private static Map<Integer, List<ControlPanelPreview.Cabinet>> layers(
            Map.Entry<Integer, List<ControlPanelPreview.Cabinet>>... rows) {
        Map<Integer, List<ControlPanelPreview.Cabinet>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ControlPanelPreview.Cabinet>> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    private static Map.Entry<Integer, List<ControlPanelPreview.Cabinet>> row(
            int key, ControlPanelPreview.Cabinet cabinet) {
        return row(key, Collections.singletonList(cabinet));
    }

    private static Map.Entry<Integer, List<ControlPanelPreview.Cabinet>> row(
            int key, List<ControlPanelPreview.Cabinet> cabinets) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, cabinets);
    }

    private static <T> ApiResult<T> failure(ServerFailure.Kind kind) {
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static final class Harness {
        final MutableClock clock = new MutableClock();
        final ManualScheduler scheduler = new ManualScheduler();
        final FakeService service = new FakeService();
        final FakeExecutor executor;
        final OnlineCustomerCoordinator controller;

        Harness() {
            this(null);
        }

        Harness(FakeExecutor executor) {
            this.executor = executor;
            controller = executor == null
                    ? new OnlineCustomerCoordinator(service, scheduler, clock, snapshot -> { })
                    : new OnlineCustomerCoordinator(
                            service, scheduler, clock, snapshot -> { }, executor);
        }
    }

    private static final class MutableClock implements OnlineCustomerCoordinator.Clock {
        long now = 1_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    private static final class ManualScheduler implements OnlineCustomerCoordinator.Scheduler {
        final ArrayDeque<Task> workers = new ArrayDeque<>();
        final ArrayDeque<Task> timers = new ArrayDeque<>();
        boolean rejectNextSubmit;

        @Override
        public OnlineCustomerCoordinator.Cancellable submit(Runnable runnable) {
            if (rejectNextSubmit) {
                rejectNextSubmit = false;
                throw new IllegalStateException("rejected");
            }
            Task task = new Task(runnable);
            workers.add(task);
            return task;
        }

        @Override
        public OnlineCustomerCoordinator.Cancellable schedule(
                Runnable runnable, long delayMillis) {
            Task task = new Task(runnable);
            timers.add(task);
            return task;
        }

        int workerCount() {
            return workers.size();
        }

        void runNextWorker() {
            Task task = workers.removeFirst();
            task.run(false);
        }

        void runAllWorkersIncludingCancelled() {
            while (!workers.isEmpty()) workers.removeFirst().run(true);
        }

        void runNextTimer() {
            timers.removeFirst().run(false);
        }

        void runNextLiveTimer() {
            while (!timers.isEmpty()) {
                Task task = timers.removeFirst();
                if (!task.cancelled) {
                    task.run(false);
                    return;
                }
            }
            throw new AssertionError("No live timer");
        }

        void poisonCancellations() {
            for (Task task : workers) task.throwOnCancel = true;
            for (Task task : timers) task.throwOnCancel = true;
        }

        private static final class Task implements OnlineCustomerCoordinator.Cancellable {
            final Runnable runnable;
            boolean cancelled;
            boolean throwOnCancel;

            Task(Runnable runnable) {
                this.runnable = runnable;
            }

            @Override
            public void cancel() {
                cancelled = true;
                if (throwOnCancel) throw new IllegalStateException("cancel rejected");
            }

            void run(boolean evenIfCancelled) {
                if (evenIfCancelled || !cancelled) runnable.run();
            }
        }
    }

    private static final class FakeService implements BusinessService {
        ApiResult<AuthenticatedUser> authResult = failure(ServerFailure.Kind.NETWORK);
        ApiResult<ControlPanelPreview> previewResult = failure(ServerFailure.Kind.NETWORK);
        ApiResult<UsedCabinetList> usedResult = failure(ServerFailure.Kind.NETWORK);
        ApiResult<AssignedCabinet> userBoardResult = failure(ServerFailure.Kind.CONTRACT);
        ApiResult<EmptyBusinessResult> openBoardResult = failure(ServerFailure.Kind.CONTRACT);
        int authCalls;
        int previewCalls;
        int usedCalls;
        int userBoardCalls;
        int openBoardCalls;
        int lastPreviewType;
        long lastAreaId;
        int lastPage;
        long lastUserBoardAreaId;
        long lastUserBoardFcId;
        BoardAction lastBoardAction;
        long lastOpenBoardFcId;
        Runnable userBoardHook;
        Runnable openBoardHook;

        @Override
        public ApiResult<AuthenticatedUser> authenticate(
                UserInfoRequest request, CallToken token) {
            authCalls++;
            return authResult;
        }

        @Override
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
                AuthenticatedUser user, String dynamicCode, CallToken token) {
            return failure(ServerFailure.Kind.CONTRACT);
        }

        @Override
        public ApiResult<ControlPanelPreview> controlPanelPreview(
                AuthenticatedUser user,
                int type,
                long areaId,
                int page,
                CallToken token) {
            previewCalls++;
            lastPreviewType = type;
            lastAreaId = areaId;
            lastPage = page;
            return previewResult;
        }

        @Override
        public ApiResult<UsedCabinetList> useCabinetList(
                AuthenticatedUser user, CallToken token) {
            usedCalls++;
            return usedResult;
        }

        @Override
        public ApiResult<AssignedCabinet> userBoard(
                AuthenticatedUser user, long areaId, long fcId, CallToken token) {
            userBoardCalls++;
            lastUserBoardAreaId = areaId;
            lastUserBoardFcId = fcId;
            if (userBoardHook != null) userBoardHook.run();
            return userBoardResult;
        }

        @Override
        public ApiResult<EmptyBusinessResult> openBoard(
                AuthenticatedUser user,
                BoardAction action,
                long fcId,
                CallToken token) {
            openBoardCalls++;
            lastBoardAction = action;
            lastOpenBoardFcId = fcId;
            if (openBoardHook != null) openBoardHook.run();
            return openBoardResult;
        }
    }

    private static final class FakeExecutor implements OnlineUnlockExecutor {
        boolean available = true;
        boolean throwOnAvailability;
        boolean throwOnExecute;
        boolean returnNullHandle;
        boolean registrationAccepted;
        boolean probeCoordinatorLockOnCancel;
        boolean blockCancellation;
        Runnable beforeRegistration;
        int executeCalls;
        AuthorizedUnlockRequest request;
        Listener listener;
        final ExecutorHandle handle = new ExecutorHandle();

        @Override
        public boolean isAvailable() {
            if (throwOnAvailability) throw new IllegalStateException("availability failed");
            return available;
        }

        @Override
        public OnlineCustomerCoordinator.Cancellable execute(
                AuthorizedUnlockRequest request, Listener listener) {
            executeCalls++;
            this.request = request;
            this.listener = listener;
            handle.authority = listener;
            handle.probeCoordinatorLock = probeCoordinatorLockOnCancel;
            handle.blockCancellation = blockCancellation;
            if (throwOnExecute) throw new IllegalStateException("execute failed");
            if (beforeRegistration != null) beforeRegistration.run();
            registrationAccepted = registerCancellation(handle);
            if (!registrationAccepted) handle.cancel();
            return returnNullHandle ? null : handle;
        }

        void complete(UnlockCoordinator.State state, String detail) {
            if (listener != null) listener.onState(state, detail);
        }

        boolean authorityIsCurrent() {
            if (listener == null) return false;
            try {
                return (Boolean) listener.getClass().getMethod("isCurrent").invoke(listener);
            } catch (ReflectiveOperationException missingAuthority) {
                return false;
            }
        }

        private boolean registerCancellation(OnlineCustomerCoordinator.Cancellable handle) {
            if (listener == null) return false;
            try {
                return (Boolean) listener.getClass()
                        .getMethod("registerCancellation",
                                OnlineCustomerCoordinator.Cancellable.class)
                        .invoke(listener, handle);
            } catch (ReflectiveOperationException missingRegistration) {
                return false;
            }
        }

        private static final class ExecutorHandle
                implements OnlineCustomerCoordinator.Cancellable {
            boolean cancelled;
            boolean probeCoordinatorLock;
            boolean cancellationObservedCoordinatorUnlocked = true;
            boolean blockCancellation;
            Listener authority;
            final CountDownLatch cancellationStarted = new CountDownLatch(1);
            final CountDownLatch allowCancellation = new CountDownLatch(1);

            @Override
            public void cancel() {
                cancelled = true;
                if (probeCoordinatorLock && authority != null) {
                    CountDownLatch completed = new CountDownLatch(1);
                    Thread probe = new Thread(() -> {
                        authority.isCurrent();
                        completed.countDown();
                    }, "coordinator-lock-probe");
                    probe.setDaemon(true);
                    probe.start();
                    try {
                        cancellationObservedCoordinatorUnlocked =
                                completed.await(500L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancellationObservedCoordinatorUnlocked = false;
                    }
                }
                if (blockCancellation) awaitCancellationRelease();
            }

            private void awaitCancellationRelease() {
                cancellationStarted.countDown();
                try {
                    allowCancellation.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
