package com.codex.lockertest.ui;

import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationStatus;
import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.runtime.InitialLayoutPolicy;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class KioskFlowModelTest {
    @Test
    public void rejectedScannerCredentialNeverEntersSelection() {
        CredentialAdmissionPolicy reject = (method, value) ->
                CredentialAdmission.rejected("服务器认证尚未配置");
        KioskFlowModel flow = new KioskFlowModel(
                reject, emptyLayoutPolicy(), TerminalReadiness.localDemoReady());

        assertFalse(flow.submitScannedCredential("opaque-not-fixed-length"));

        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
    }

    @Test
    public void acceptedScannerAdmitsOpaqueValueExactlyOnceAndUsesPolicyMethod() {
        final int[] calls = {0};
        final UnlockMethod[] requestedMethod = {null};
        final String[] requestedValue = {null};
        CredentialAdmissionPolicy acceptQr = (method, value) -> {
            calls[0]++;
            requestedMethod[0] = method;
            requestedValue[0] = value;
            return CredentialAdmission.accepted(UnlockMethod.QR);
        };
        KioskFlowModel flow = new KioskFlowModel(
                acceptQr, emptyLayoutPolicy(), TerminalReadiness.localDemoReady());
        String opaque = "not-a-known-demo-value~with-prefix-000";

        assertTrue(flow.submitScannedCredential(opaque));

        assertEquals(1, calls[0]);
        assertEquals(UnlockMethod.ID_CARD, requestedMethod[0]);
        assertEquals(opaque, requestedValue[0]);
        assertEquals(UnlockMethod.QR, flow.pendingCredentialMethod());
        assertEquals(opaque, flow.pendingCredential());
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
    }

    @Test
    public void faceRecognitionStartsOnlyFromAnExplicitHomeAction() {
        KioskFlowModel flow = newFlow();

        assertFalse(flow.openUnavailable(UnlockMethod.FACE));
        assertTrue(flow.beginFaceRecognition());

        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
        assertNull(flow.pendingFaceVerification());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertFalse(flow.beginFaceRecognition());
        assertFalse(flow.openAdminPin());
    }

    @Test
    public void terminalFaceResultsRemainOnFaceAndCannotReachLockerConfirmation() {
        KioskFlowModel flow = newFlow();
        assertTrue(flow.beginFaceRecognition());

        assertFalse(flow.acceptFaceVerification(null, 1_000L));
        assertFalse(flow.acceptFaceVerification(
                FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.NOT_PASSED, "request-not-passed"),
                1_001L));
        assertFalse(flow.acceptFaceVerification(
                FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.CANCELLED, "request-cancelled"),
                1_002L));

        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
        assertNull(flow.pendingFaceVerification());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertFalse(flow.confirmLocker(target(LockerZone.A, 1)));
        assertFalse(flow.confirmLocker());
        assertEquals(KioskFlowModel.ConfirmResult.INVALID_SELECTION,
                flow.confirmLockerAt(target(LockerZone.A, 1), 1_003L));
    }

    @Test
    public void leavingFaceRecognitionClearsItsStateAndPreservesLegacyHomeFlows() {
        KioskFlowModel flow = newFlow();
        assertTrue(flow.beginFaceRecognition());

        assertTrue(flow.back());

        assertFullyReturnedHome(flow);
        assertNull(flow.pendingFaceVerification());
        assertTrue(flow.submitCredential(UnlockMethod.PASSWORD, "123456"));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertEquals(UnlockMethod.PASSWORD, flow.pendingCredentialMethod());
        assertEquals("123456", flow.pendingCredential());
    }

    @Test
    public void passiveHomeScannerAcceptsIdCardAndQrIntoTheSameDiscoveryFlow() {
        String qr = "111993413628001787216027-00144049324404404044044~712~1~3~"
                + "30303030303137373331";
        KioskFlowModel idFlow = newFlow();
        KioskFlowModel qrFlow = newFlow();

        assertTrue(idFlow.submitScannedCredential("0014872138"));
        assertTrue(qrFlow.submitScannedCredential(qr));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, idFlow.screen());
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, qrFlow.screen());
        assertEquals(UnlockMethod.ID_CARD, idFlow.pendingCredentialMethod());
        assertEquals(UnlockMethod.QR, qrFlow.pendingCredentialMethod());
        assertEquals("0014872138", idFlow.pendingCredential());
        assertEquals(qr, qrFlow.pendingCredential());
        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING,
                idFlow.lockerSelection().discoveryState());
        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING,
                qrFlow.lockerSelection().discoveryState());
        assertNull(idFlow.pendingTarget());
        assertNull(qrFlow.pendingTarget());
    }

    @Test
    public void unknownOrOutOfContextPassiveScanDoesNotChangeTheFlow() {
        KioskFlowModel flow = newFlow();

        assertFalse(flow.submitScannedCredential("001472138"));
        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());

        assertTrue(flow.beginFaceRecognition());
        assertFalse(flow.submitScannedCredential("0014872138"));
        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
    }

    @Test
    public void validCredentialStartsDiscoveryAndStoresCredentialWithoutSelectingALocker() {
        KioskFlowModel flow = newFlow();

        assertTrue(flow.submitCredential(UnlockMethod.PASSWORD, "123456"));

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertEquals(UnlockMethod.PASSWORD, flow.pendingCredentialMethod());
        assertEquals("123456", flow.pendingCredential());
        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING,
                flow.lockerSelection().discoveryState());
        assertTrue(flow.lockerSelection().onlineZones().isEmpty());
        assertNull(flow.pendingTarget());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
    }

    @Test
    public void confirmationIsRejectedUntilACompleteDiscoverySnapshotExists() {
        KioskFlowModel flow = validPasswordDetectingFlow();
        LockerTarget a1 = target(LockerZone.A, 1);

        assertFalse(flow.confirmLocker(a1));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertNull(flow.pendingTarget());

        assertTrue(flow.applyDiscoverySnapshot(Collections.singletonList(LockerZone.A)));
        assertTrue(flow.confirmLocker(a1));
        assertEquals(KioskFlowModel.Screen.RESULT, flow.screen());
        assertSame(a1, flow.pendingTarget());
    }

    @Test
    public void confirmationPreflightAcceptsOnlyTheCurrentSelectionWithoutChangingAnyState() {
        KioskFlowModel flow = dynamicReadyPasswordFlow();
        assertTrue(flow.lockerSelection().nextPage());
        assertTrue(flow.lockerSelection().selectSlot("slot-alpha-2"));
        LockerTarget selected = flow.lockerSelection().selectedTarget();
        FlowState before = FlowState.capture(flow);

        assertEquals(KioskFlowModel.ConfirmResult.ACCEPTED,
                checkLockerConfirmationAt(flow, selected, 8_000L));

        before.assertUnchanged(flow);
        LockerTarget structuralCopy = target(selected.zone(), selected.localLock());
        assertEquals(KioskFlowModel.ConfirmResult.INVALID_SELECTION,
                checkLockerConfirmationAt(flow, structuralCopy, 8_000L));
        before.assertUnchanged(flow);
    }

    @Test
    public void confirmationPreflightRejectsIncompleteSelectionWithoutRestoringOrSending() {
        KioskFlowModel flow = dynamicReadyPasswordFlow();
        FlowState before = FlowState.capture(flow);

        assertEquals(KioskFlowModel.ConfirmResult.INVALID_SELECTION,
                checkLockerConfirmationAt(flow, target(LockerZone.B, 3), 8_000L));

        before.assertUnchanged(flow);
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertNull(flow.pendingTarget());
    }

    @Test
    public void confirmationCommitFailsClosedIfSelectionChangesAfterSuccessfulPreflight() {
        KioskFlowModel flow = dynamicReadyPasswordFlow();
        assertTrue(flow.lockerSelection().selectSlot("slot-alpha-1"));
        LockerTarget originallySelected = flow.lockerSelection().selectedTarget();
        assertEquals(KioskFlowModel.ConfirmResult.ACCEPTED,
                checkLockerConfirmationAt(flow, originallySelected, 8_000L));
        assertTrue(flow.lockerSelection().selectArea("area-beta"));
        assertTrue(flow.lockerSelection().selectSlot("slot-beta-3"));
        LockerTarget replacement = flow.lockerSelection().selectedTarget();

        assertEquals(KioskFlowModel.ConfirmResult.INVALID_SELECTION,
                flow.confirmLockerAt(originallySelected, 8_000L));

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(replacement, flow.lockerSelection().selectedTarget());
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertNull(flow.pendingTarget());
        assertNull(flow.pendingLockerLabel());
    }

    @Test
    public void emptyCompletedSnapshotIsNoZonesAndNeverFabricatesZoneA() {
        KioskFlowModel flow = validPasswordDetectingFlow();

        assertTrue(flow.applyDiscoverySnapshot(Collections.<LockerZone>emptyList()));

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertEquals(LockerSelectionModel.DiscoveryState.NO_ZONES,
                flow.lockerSelection().discoveryState());
        assertTrue(flow.lockerSelection().onlineZones().isEmpty());
        assertFalse(flow.confirmLocker(target(LockerZone.A, 1)));
        assertNull(flow.pendingTarget());
    }

    @Test
    public void snapshotIsDefensiveAndOfflineTargetCannotBeConfirmed() {
        KioskFlowModel flow = validPasswordDetectingFlow();
        List<LockerZone> callerSnapshot = new ArrayList<>(
                Arrays.asList(LockerZone.C, LockerZone.B));

        assertTrue(flow.applyDiscoverySnapshot(callerSnapshot));
        callerSnapshot.clear();

        assertEquals(Arrays.asList(LockerZone.B, LockerZone.C),
                flow.lockerSelection().onlineZones());
        assertFalse(flow.confirmLocker(target(LockerZone.A, 6)));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertNull(flow.pendingTarget());
    }

    @Test
    public void aSecondSnapshotIsRejectedAfterDetectionCompletes() {
        KioskFlowModel flow = validPasswordDetectingFlow();

        assertTrue(flow.applyDiscoverySnapshot(Collections.singletonList(LockerZone.B)));
        assertFalse(flow.applyDiscoverySnapshot(Collections.singletonList(LockerZone.C)));
        assertFalse(flow.applyLayoutSnapshot(dynamicLayout()));

        assertEquals(Collections.singletonList(LockerZone.B),
                flow.lockerSelection().onlineZones());
    }

    @Test
    public void dynamicLayoutNavigatesMultipleAreasAndPagesThenFreezesTheLogicalSelection() {
        KioskFlowModel flow = validPasswordDetectingFlow();
        LockerLayoutSnapshot snapshot = dynamicLayout();

        assertTrue(flow.applyLayoutSnapshot(snapshot));
        assertSame(snapshot, flow.lockerSelection().layoutSnapshot());
        assertTrue(flow.lockerSelection().onlineZones().isEmpty());
        assertEquals("area-alpha", flow.lockerSelection().activeArea().id());
        assertEquals("page-alpha-1", flow.lockerSelection().activePage().id());

        assertTrue(flow.lockerSelection().nextPage());
        assertEquals("page-alpha-2", flow.lockerSelection().activePage().id());
        assertTrue(flow.lockerSelection().selectArea("area-beta"));
        assertEquals("page-beta-1", flow.lockerSelection().activePage().id());

        LockerTarget callerTarget = target(LockerZone.A, 2);
        assertTrue(flow.confirmLocker(callerTarget));

        assertEquals(KioskFlowModel.Screen.RESULT, flow.screen());
        assertSame(callerTarget, flow.pendingTarget());
        assertEquals("ALPHA-TWO", flow.pendingLockerLabel());
        assertSame(snapshot.areas().get(0), flow.lockerSelection().activeArea());
        assertSame(snapshot.areas().get(0).pages().get(1),
                flow.lockerSelection().activePage());
        assertSame(snapshot.areas().get(0).pages().get(1).slots().get(0),
                flow.lockerSelection().selectedSlot());
        assertSame(callerTarget, flow.lockerSelection().selectedTarget());
        assertFalse(flow.lockerSelection().isInteractionEnabled());

        LockerLayoutSnapshot replacement = singleSlotLayout(
                "replacement", "REPLACEMENT", target(LockerZone.C, 9));
        assertFalse(flow.applyLayoutSnapshot(replacement));
        assertSame(snapshot, flow.lockerSelection().layoutSnapshot());
        assertEquals("ALPHA-TWO", flow.pendingLockerLabel());
    }

    @Test
    public void sharedViewAlreadySendingAcceptsOnlyItsExactSelectedTargetInstance() {
        KioskFlowModel accepted = dynamicReadyPasswordFlow();
        assertTrue(accepted.lockerSelection().selectSlot("slot-alpha-1"));
        LockerTarget exactTarget = accepted.lockerSelection().selectedTarget();
        assertTrue(accepted.lockerSelection().beginSending());

        assertTrue(accepted.confirmLocker(exactTarget));
        assertEquals(KioskFlowModel.Screen.RESULT, accepted.screen());
        assertSame(exactTarget, accepted.pendingTarget());
        assertEquals("ALPHA-ONE", accepted.pendingLockerLabel());

        KioskFlowModel rejected = dynamicReadyPasswordFlow();
        assertTrue(rejected.lockerSelection().selectSlot("slot-alpha-1"));
        LockerTarget selected = rejected.lockerSelection().selectedTarget();
        LockerTarget structuralCopy = target(
                selected.zone(), selected.localLock());
        assertTrue(rejected.lockerSelection().beginSending());

        assertFalse(rejected.confirmLocker(structuralCopy));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, rejected.screen());
        assertNull(rejected.pendingTarget());
        assertNull(rejected.pendingLockerLabel());
        assertSame(selected, rejected.lockerSelection().selectedTarget());
        assertFalse(rejected.lockerSelection().isInteractionEnabled());
    }

    @Test
    public void aSecondConfirmationIsRejectedWithoutReplacingTheFrozenRequest() {
        KioskFlowModel flow = dynamicReadyPasswordFlow();
        LockerTarget first = target(LockerZone.A, 1);

        assertTrue(flow.confirmLocker(first));
        assertFalse(flow.confirmLocker(target(LockerZone.B, 3)));

        assertSame(first, flow.pendingTarget());
        assertEquals("ALPHA-ONE", flow.pendingLockerLabel());
        assertEquals(KioskFlowModel.Screen.RESULT, flow.screen());
    }

    @Test
    public void unlockFailureRetryKeepsEveryExactReferenceAndRequiresExplicitReconfirm() {
        KioskFlowModel flow = dynamicReadyPasswordFlow();
        assertTrue(flow.lockerSelection().nextPage());
        assertTrue(flow.lockerSelection().selectSlot("slot-alpha-2"));
        LockerArea area = flow.lockerSelection().activeArea();
        LockerPage page = flow.lockerSelection().activePage();
        LockerSlot slot = flow.lockerSelection().selectedSlot();
        LockerTarget target = flow.lockerSelection().selectedTarget();
        assertTrue(flow.lockerSelection().beginSending());
        assertTrue(flow.confirmLocker(target));
        assertTrue(flow.finishUnlockFailure());

        assertTrue(flow.retry());

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(area, flow.lockerSelection().activeArea());
        assertSame(page, flow.lockerSelection().activePage());
        assertSame(slot, flow.lockerSelection().selectedSlot());
        assertSame(target, flow.lockerSelection().selectedTarget());
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertTrue(flow.lockerSelection().canConfirm());
        assertNull(flow.pendingTarget());
        assertNull(flow.pendingLockerLabel());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
        assertFalse(flow.finishUnlockFailure());

        assertTrue(flow.confirmLocker(target));
        assertEquals(KioskFlowModel.Screen.RESULT, flow.screen());
        assertSame(target, flow.pendingTarget());
    }

    @Test
    public void anyReferenceDriftBeforeFailureRetryFailsClosedToHome() {
        KioskFlowModel pageDrift = dynamicReadyPasswordFlow();
        LockerTarget alphaOne = target(LockerZone.A, 1);
        assertTrue(pageDrift.confirmLocker(alphaOne));
        assertTrue(pageDrift.finishUnlockFailure());
        assertTrue(pageDrift.lockerSelection().nextPage());

        assertFalse(pageDrift.retry());
        assertFullyReturnedHome(pageDrift);

        KioskFlowModel targetDrift = dynamicReadyPasswordFlow();
        LockerTarget exactCaller = target(LockerZone.A, 1);
        assertTrue(targetDrift.confirmLocker(exactCaller));
        assertTrue(targetDrift.finishUnlockFailure());
        assertTrue(targetDrift.lockerSelection().restoreTarget(target(LockerZone.A, 1)));

        assertFalse(targetDrift.retry());
        assertFullyReturnedHome(targetDrift);
    }

    @Test
    public void mainRetryCallbackRendersHomeWhenRetryFailsClosedToHome() throws IOException {
        String source = mainActivitySource();
        String callback = sourceSlice(
                source,
                "    private ResultOverlay createResultOverlay()",
                "    private void showAdminPin()");
        int failedRetry = callback.indexOf("if (!flow.retry()) {");
        int homeGuard = callback.indexOf(
                "if (flow.screen() == KioskFlowModel.Screen.HOME)", failedRetry);
        int renderHome = callback.indexOf("renderScreen();", homeGuard);
        int failedRetryReturn = callback.indexOf("return;", failedRetry);

        assertTrue(failedRetry >= 0);
        assertTrue(homeGuard > failedRetry);
        assertTrue(renderHome > homeGuard);
        assertTrue(renderHome < failedRetryReturn);
    }

    @Test
    public void mainRejectsDetachedConfirmSourceAndRestoresCurrentSelector() throws IOException {
        String source = mainActivitySource();
        String render = sourceSlice(
                source,
                "    private void renderLockerSelection()",
                "    private ResultOverlay createResultOverlay()");
        String submit = sourceSlice(
                source,
                "    private void submitLocker(",
                "    private void enqueueDiscoveryCompleted(");
        int sourceGuard = submit.indexOf("source != lockerSelectionView");
        int currentViewGuard = submit.indexOf("lockerSelectionView != null", sourceGuard);
        int selectionScreenGuard = submit.indexOf(
                "flow.screen() == KioskFlowModel.Screen.LOCKER_SELECTION", sourceGuard);
        int restoreCurrentSelector = submit.indexOf(
                "lockerSelectionView.setSending(false);", sourceGuard);
        int staleSourceReturn = submit.indexOf("return;", restoreCurrentSelector);
        int flowPreflight = submit.indexOf("flow.checkLockerConfirmationAt(target,");
        int faceCredentialExpired = submit.indexOf(
                "KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED", flowPreflight);
        int expiryCommit = submit.indexOf(
                "flow.confirmLockerAt(target, confirmationEpochMillis)",
                faceCredentialExpired);

        assertTrue(render.contains("submitLocker(selection, target);"));
        assertTrue(submit.contains(
                "submitLocker(LockerSelectionView source, LockerTarget target)"));
        assertTrue(sourceGuard >= 0);
        assertTrue(currentViewGuard > sourceGuard);
        assertTrue(selectionScreenGuard > currentViewGuard);
        assertTrue(restoreCurrentSelector > selectionScreenGuard);
        assertTrue(staleSourceReturn > restoreCurrentSelector);
        assertTrue(flowPreflight > staleSourceReturn);
        assertTrue(faceCredentialExpired > flowPreflight);
        assertTrue(expiryCommit > faceCredentialExpired);
        String staleSourceBranch = submit.substring(sourceGuard, staleSourceReturn);
        assertFalse(staleSourceBranch.contains("flow.confirmLockerAt("));
        assertFalse(staleSourceBranch.contains("invalidateCustomerWork("));
        assertFalse(staleSourceBranch.contains("unlockCoordinator.start("));
    }

    @Test
    public void mainRejectsDetachedCancelSourceBeforeInvalidatingWork() throws IOException {
        String source = mainActivitySource();
        String render = sourceSlice(
                source,
                "    private void renderLockerSelection()",
                "    private ResultOverlay createResultOverlay()");
        int cancelCallback = render.indexOf("public void onCancel()");
        int sourceGuard = render.indexOf(
                "selection != lockerSelectionView", cancelCallback);
        int invalidate = render.indexOf("invalidateCustomerWork(true);", cancelCallback);

        assertTrue(cancelCallback >= 0);
        assertTrue(sourceGuard > cancelCallback);
        assertTrue(invalidate > sourceGuard);
    }

    @Test
    public void unlockFailureRetryRestoresTheExactTargetAndRequiresExplicitConfirmation() {
        KioskFlowModel flow = readyPasswordFlow(LockerZone.A, LockerZone.C);
        LockerTarget c12 = target(LockerZone.C, 12);

        assertTrue(flow.confirmLocker(c12));
        assertSame(c12, flow.pendingTarget());
        assertTrue(flow.finishUnlockFailure());
        assertEquals(KioskFlowModel.ResultContext.UNLOCK_FAILURE, flow.resultContext());

        assertTrue(flow.retry());

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertEquals(Arrays.asList(LockerZone.A, LockerZone.C),
                flow.lockerSelection().onlineZones());
        assertSame(c12, flow.lockerSelection().selectedTarget());
        assertNull(flow.pendingTarget());
        assertTrue(flow.lockerSelection().canConfirm());

        assertTrue(flow.confirmLocker(c12));
        assertSame(c12, flow.pendingTarget());
        assertEquals(KioskFlowModel.Screen.RESULT, flow.screen());
    }

    @Test
    public void discoveryFailureRetryStartsNewDetectionWhileUnlockRetryKeepsTopology() {
        KioskFlowModel discovery = validPasswordDetectingFlow();
        assertTrue(discovery.finishDiscoveryFailure());
        assertEquals(KioskFlowModel.Screen.RESULT, discovery.screen());
        assertEquals(KioskFlowModel.ResultContext.DISCOVERY_FAILURE,
                discovery.resultContext());

        assertTrue(discovery.retry());
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, discovery.screen());
        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING,
                discovery.lockerSelection().discoveryState());
        assertTrue(discovery.lockerSelection().onlineZones().isEmpty());

        KioskFlowModel unlock = readyPasswordFlow(LockerZone.B);
        LockerTarget b3 = target(LockerZone.B, 3);
        assertTrue(unlock.confirmLocker(b3));
        assertTrue(unlock.finishUnlockFailure());
        assertTrue(unlock.retry());
        assertEquals(LockerSelectionModel.DiscoveryState.READY,
                unlock.lockerSelection().discoveryState());
        assertEquals(Collections.singletonList(LockerZone.B),
                unlock.lockerSelection().onlineZones());
        assertSame(b3, unlock.lockerSelection().selectedTarget());
    }

    @Test
    public void backFromDiscoveryFailureRestartsDetection() {
        KioskFlowModel flow = validPasswordDetectingFlow();
        assertTrue(flow.finishDiscoveryFailure());

        assertTrue(flow.back());

        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING,
                flow.lockerSelection().discoveryState());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
    }

    @Test
    public void successCanOnlyReturnHomeAndCannotReopenSelection() {
        KioskFlowModel flow = readyPasswordFlow(LockerZone.A);
        LockerTarget a4 = target(LockerZone.A, 4);
        assertTrue(flow.confirmLocker(a4));

        assertTrue(flow.finishSuccessfulRequest());

        assertEquals(KioskFlowModel.ResultContext.UNLOCK_SUCCESS, flow.resultContext());
        assertSame(a4, flow.pendingTarget());
        assertEquals("A4", flow.pendingLockerLabel());
        assertFalse(flow.retry());
        assertTrue(flow.back());
        assertFullyReturnedHome(flow);
        assertFalse(flow.back());
    }

    @Test
    public void compatibilityDiscoveryBuildsExactAbcSparseFourByEightColumnMajorLayouts() {
        KioskFlowModel flow = validPasswordDetectingFlow();

        assertTrue(flow.applyDiscoverySnapshot(Arrays.asList(
                LockerZone.C, LockerZone.A, LockerZone.B, LockerZone.C)));

        LockerLayoutSnapshot snapshot = flow.lockerSelection().layoutSnapshot();
        assertEquals(Arrays.asList(LockerZone.A, LockerZone.B, LockerZone.C),
                flow.lockerSelection().onlineZones());
        assertEquals(3, snapshot.areas().size());
        for (int areaIndex = 0; areaIndex < 3; areaIndex++) {
            LockerZone zone = LockerZone.values()[areaIndex];
            LockerArea area = snapshot.areas().get(areaIndex);
            assertEquals("legacy-area-" + zone.name(), area.id());
            assertEquals(1, area.pages().size());
            LockerPage page = area.pages().get(0);
            assertEquals(4, page.rows());
            assertEquals(8, page.columns());
            assertEquals(12, page.slots().size());
            assertNull(page.slotAt(1, 4));
            LockerSlot first = page.slotAt(1, 1);
            assertEquals(zone.name() + "1", first.displayLabel());
            assertEquals(zone, first.target().zone());
            assertEquals(zone.boardAddress(), first.target().boardAddress());
            assertEquals(1, first.target().localLock());
            assertSame(FeedbackPolarity.SHORT_WHEN_LOCKED,
                    first.target().feedbackPolarity());
            LockerSlot twelfth = page.slotAt(4, 3);
            assertEquals(zone.name() + "12", twelfth.displayLabel());
            assertEquals(12, twelfth.target().localLock());
        }
    }

    @Test
    public void invalidCredentialProducesNoDiscoveryOrSerialIntent() {
        KioskFlowModel flow = newFlow();
        enter(flow.credentials(), UnlockMethod.PASSWORD, "123457");

        assertFalse(flow.submitCredential());

        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertEquals(LockerSelectionModel.DiscoveryState.READY,
                flow.lockerSelection().discoveryState());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.pendingTarget());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
    }

    @Test
    public void cancelDuringDetectionReturnsHomeAndClearsSensitiveCredential() {
        KioskFlowModel flow = validPasswordDetectingFlow();

        assertTrue(flow.cancelLockerSelection());

        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertEquals("", flow.credentials().rawValue(UnlockMethod.PASSWORD));
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.pendingTarget());
    }

    @Test
    public void backDuringDetectionReturnsHomeAndCannotLeaveASelectionBehind() {
        KioskFlowModel flow = validPasswordDetectingFlow();

        assertTrue(flow.back());

        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.lockerSelection().selectedTarget());
        assertNull(flow.pendingTarget());
    }

    @Test
    public void unavailableAndAdminNavigationRemainHomeOnlyFlows() {
        KioskFlowModel flow = newFlow();

        assertFalse(flow.openUnavailable(UnlockMethod.FACE));
        assertTrue(flow.beginFaceRecognition());
        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
        assertFalse(flow.openAdminPin());
        assertFalse(flow.openUnavailable(UnlockMethod.PALM));
        assertTrue(flow.back());
        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());

        assertTrue(flow.openAdminPin());
        assertEquals(KioskFlowModel.Screen.ADMIN_PIN, flow.screen());
        assertFalse(flow.openAdminPin());
        assertFalse(flow.openUnavailable(UnlockMethod.QR));
        assertTrue(flow.back());
        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());

        KioskFlowModel selecting = validPasswordDetectingFlow();
        assertFalse(selecting.openAdminPin());
        assertFalse(selecting.openUnavailable(UnlockMethod.FACE));
        assertFalse(selecting.beginFaceRecognition());
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, selecting.screen());
    }

    private static KioskFlowModel validPasswordDetectingFlow() {
        KioskFlowModel flow = newFlow();
        assertTrue(flow.submitCredential(UnlockMethod.PASSWORD, "123456"));
        return flow;
    }

    private static KioskFlowModel newFlow() {
        return new KioskFlowModel(
                RuntimePolicyFixtures.demoCredentialPolicy(),
                RuntimePolicyFixtures.legacyLayoutPolicy(),
                TerminalReadiness.localDemoReady());
    }

    private static InitialLayoutPolicy emptyLayoutPolicy() {
        return new InitialLayoutPolicy() {
            @Override
            public LockerLayoutSnapshot initialLayout() {
                return null;
            }

            @Override
            public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
                return null;
            }
        };
    }

    private static KioskFlowModel readyPasswordFlow(LockerZone... zones) {
        KioskFlowModel flow = validPasswordDetectingFlow();
        assertTrue(flow.applyDiscoverySnapshot(Arrays.asList(zones)));
        return flow;
    }

    private static KioskFlowModel dynamicReadyPasswordFlow() {
        KioskFlowModel flow = validPasswordDetectingFlow();
        assertTrue(flow.applyLayoutSnapshot(dynamicLayout()));
        return flow;
    }

    private static LockerLayoutSnapshot dynamicLayout() {
        LockerTarget alphaOne = target(LockerZone.A, 1);
        LockerTarget alphaTwo = target(LockerZone.A, 2);
        LockerTarget betaThree = target(LockerZone.B, 3);
        LockerPage alphaPageOne = new LockerPage(
                "page-alpha-1",
                17,
                2,
                8,
                Collections.singletonList(new LockerSlot(
                        "slot-alpha-1", "ALPHA-ONE", 1, 1, true, alphaOne)));
        LockerPage alphaPageTwo = new LockerPage(
                "page-alpha-2",
                44,
                4,
                8,
                Collections.singletonList(new LockerSlot(
                        "slot-alpha-2", "ALPHA-TWO", 4, 8, true, alphaTwo)));
        LockerPage betaPage = new LockerPage(
                "page-beta-1",
                9,
                1,
                4,
                Collections.singletonList(new LockerSlot(
                        "slot-beta-3", "BETA-THREE", 1, 4, true, betaThree)));
        return new LockerLayoutSnapshot(903L, Arrays.asList(
                new LockerArea("area-alpha", "Alpha", Arrays.asList(
                        alphaPageOne, alphaPageTwo)),
                new LockerArea("area-beta", "Beta", Collections.singletonList(betaPage))));
    }

    private static LockerLayoutSnapshot singleSlotLayout(
            String id, String displayLabel, LockerTarget target) {
        LockerSlot slot = new LockerSlot(
                "slot-" + id, displayLabel, 1, 1, true, target);
        LockerPage page = new LockerPage(
                "page-" + id, 1, 1, 1, Collections.singletonList(slot));
        LockerArea area = new LockerArea(
                "area-" + id, id, Collections.singletonList(page));
        return new LockerLayoutSnapshot(904L, Collections.singletonList(area));
    }

    private static void assertFullyReturnedHome(KioskFlowModel flow) {
        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
        assertNull(flow.pendingTarget());
        assertNull(flow.pendingLockerLabel());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.lockerSelection().selectedSlot());
        assertNull(flow.lockerSelection().selectedTarget());
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertEquals("", flow.credentials().rawValue(UnlockMethod.PASSWORD));
    }

    private static LockerTarget target(LockerZone zone, int localLock) {
        return new LockerTarget(
                zone,
                zone.boardAddress(),
                localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static KioskFlowModel.ConfirmResult checkLockerConfirmationAt(
            KioskFlowModel flow, LockerTarget target, long nowEpochMillis) {
        try {
            Method method = KioskFlowModel.class.getMethod(
                    "checkLockerConfirmationAt", LockerTarget.class, long.class);
            return (KioskFlowModel.ConfirmResult) method.invoke(
                    flow, target, nowEpochMillis);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("missing pure confirmation preflight", exception);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("confirmation preflight is not public", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("confirmation preflight threw", exception.getCause());
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("missing flow state: " + name, exception);
        }
    }

    private static final class FlowState {
        private final KioskFlowModel.Screen screen;
        private final KioskFlowModel.ResultContext resultContext;
        private final UnlockMethod unavailableMethod;
        private final UnlockMethod credentialMethod;
        private final String credential;
        private final FaceVerificationResult faceVerification;
        private final Object pendingArea;
        private final Object pendingPage;
        private final Object pendingSlot;
        private final LockerTarget pendingTarget;
        private final String pendingLabel;
        private final LockerSelectionModel.DiscoveryState discoveryState;
        private final List<LockerZone> onlineZones;
        private final LockerLayoutSnapshot layout;
        private final int activeAreaIndex;
        private final int activePageIndex;
        private final LockerArea activeArea;
        private final LockerPage activePage;
        private final LockerSlot selectedSlot;
        private final LockerTarget selectedTarget;
        private final boolean interactionEnabled;
        private final boolean canConfirm;
        private final Object legacyCompatibilityLayout;
        private final UnlockMethod activeCredentialMethod;
        private final String phoneCredential;
        private final String passwordCredential;
        private final String credentialValidationError;

        private FlowState(KioskFlowModel flow) {
            screen = flow.screen();
            resultContext = flow.resultContext();
            unavailableMethod = flow.unavailableMethod();
            credentialMethod = flow.pendingCredentialMethod();
            credential = flow.pendingCredential();
            faceVerification = flow.pendingFaceVerification();
            pendingArea = field(flow, "pendingArea");
            pendingPage = field(flow, "pendingPage");
            pendingSlot = field(flow, "pendingSlot");
            pendingTarget = flow.pendingTarget();
            pendingLabel = flow.pendingLockerLabel();
            discoveryState = flow.lockerSelection().discoveryState();
            onlineZones = new ArrayList<>(flow.lockerSelection().onlineZones());
            layout = flow.lockerSelection().layoutSnapshot();
            activeAreaIndex = flow.lockerSelection().activeAreaIndex();
            activePageIndex = flow.lockerSelection().activePageIndex();
            activeArea = flow.lockerSelection().activeArea();
            activePage = flow.lockerSelection().activePage();
            selectedSlot = flow.lockerSelection().selectedSlot();
            selectedTarget = flow.lockerSelection().selectedTarget();
            interactionEnabled = flow.lockerSelection().isInteractionEnabled();
            canConfirm = flow.lockerSelection().canConfirm();
            legacyCompatibilityLayout = field(
                    flow.lockerSelection(), "legacyCompatibilityLayout");
            activeCredentialMethod = flow.credentials().activeMethod();
            phoneCredential = flow.credentials().rawValue(UnlockMethod.PHONE);
            passwordCredential = flow.credentials().rawValue(UnlockMethod.PASSWORD);
            credentialValidationError = flow.credentials().validationError();
        }

        private static FlowState capture(KioskFlowModel flow) {
            return new FlowState(flow);
        }

        private void assertUnchanged(KioskFlowModel flow) {
            assertSame(screen, flow.screen());
            assertSame(resultContext, flow.resultContext());
            assertSame(unavailableMethod, flow.unavailableMethod());
            assertSame(credentialMethod, flow.pendingCredentialMethod());
            assertEquals(credential, flow.pendingCredential());
            assertSame(faceVerification, flow.pendingFaceVerification());
            assertSame(pendingArea, field(flow, "pendingArea"));
            assertSame(pendingPage, field(flow, "pendingPage"));
            assertSame(pendingSlot, field(flow, "pendingSlot"));
            assertSame(pendingTarget, flow.pendingTarget());
            assertEquals(pendingLabel, flow.pendingLockerLabel());
            assertSame(discoveryState, flow.lockerSelection().discoveryState());
            assertEquals(onlineZones, flow.lockerSelection().onlineZones());
            assertSame(layout, flow.lockerSelection().layoutSnapshot());
            assertEquals(activeAreaIndex, flow.lockerSelection().activeAreaIndex());
            assertEquals(activePageIndex, flow.lockerSelection().activePageIndex());
            assertSame(activeArea, flow.lockerSelection().activeArea());
            assertSame(activePage, flow.lockerSelection().activePage());
            assertSame(selectedSlot, flow.lockerSelection().selectedSlot());
            assertSame(selectedTarget, flow.lockerSelection().selectedTarget());
            assertEquals(interactionEnabled,
                    flow.lockerSelection().isInteractionEnabled());
            assertEquals(canConfirm, flow.lockerSelection().canConfirm());
            assertEquals(legacyCompatibilityLayout,
                    field(flow.lockerSelection(), "legacyCompatibilityLayout"));
            assertSame(activeCredentialMethod, flow.credentials().activeMethod());
            assertEquals(phoneCredential,
                    flow.credentials().rawValue(UnlockMethod.PHONE));
            assertEquals(passwordCredential,
                    flow.credentials().rawValue(UnlockMethod.PASSWORD));
            assertEquals(credentialValidationError,
                    flow.credentials().validationError());
        }
    }

    private static String mainActivitySource() throws IOException {
        return new String(Files.readAllBytes(Paths.get(
                "app", "src", "main", "java", "com", "codex", "lockertest",
                "MainActivity.java")), StandardCharsets.UTF_8);
    }

    private static String sourceSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static void enter(HomeCredentialModel credentials,
            UnlockMethod method, String digits) {
        assertTrue(credentials.select(method));
        for (char digit : digits.toCharArray()) {
            assertTrue(credentials.pressDigit(digit));
        }
    }
}
