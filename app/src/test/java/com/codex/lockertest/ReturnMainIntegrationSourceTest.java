package com.codex.lockertest;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Android-boundary contracts for the separate, server-authorized RETURN journey. */
public final class ReturnMainIntegrationSourceTest {
    private static final String MAIN =
            "app/src/main/java/com/codex/lockertest/MainActivity.java";

    @Test
    public void homeStartsSeparateReturnJourneyWithoutOrdinaryDiscovery() throws Exception {
        String source = read(MAIN);
        String home = javaMethodBody(source, "createHomeView");
        assertContains(home, "void onReturnRequested()");
        assertContains(home, "beginReturnJourney()");
        String begin = javaMethodBody(source, "beginReturnJourney");
        assertContains(begin, "returnFlowController.begin()");
        assertContains(begin, "renderReturnSnapshot()");
        assertFalse(begin.contains("flow.submitCredential("));
        assertFalse(begin.contains("startDiscoveryOperation("));
    }

    @Test
    public void passiveScannerRoutesByActiveJourneyWithoutLoggingCredential() throws Exception {
        String source = read(MAIN);
        String active = javaMethodBody(source, "isPassiveCredentialCaptureActive");
        assertContains(active, "isReturnAuthenticationActive()");
        String finish = javaMethodBody(source, "finishPassiveCredentialInput");
        assertContains(finish, "submitReturnScannedCredential(");
        assertContains(finish, "flow.submitScannedCredential(rawCredential)");
        assertFalse(finish.contains("appendCustomerLog(rawCredential"));
        assertFalse(finish.contains("startDiscoveryOperation();\n            returnFlowController"));
    }

    @Test
    public void facePurposeValidatesIssuedTicketDirectlyAndNeverGrantsUnlockAuthority()
            throws Exception {
        String source = read(MAIN);
        assertContains(source, "enum FaceJourney");
        assertContains(source, "NORMAL");
        assertContains(source, "RETURN");
        String complete = javaMethodBody(source, "completeFaceRecognition");
        assertContains(complete, "completeReturnFaceRecognition(");
        String returnComplete = javaMethodBody(source, "completeReturnFaceRecognition");
        assertTrue(returnComplete.indexOf("if (now < 0L)")
                < returnComplete.indexOf("verificationEnvironment.validateTicket("));
        assertTrue(returnComplete.indexOf("return;", returnComplete.indexOf("if (now < 0L)"))
                < returnComplete.indexOf("verificationEnvironment.validateTicket("));
        assertContains(returnComplete, "verificationEnvironment.validateTicket(");
        assertContains(returnComplete, "success.expectedRequestId()");
        assertContains(returnComplete, "faceSubsystem.deviceBinding()");
        assertContains(returnComplete, "faceSubsystem.processBinding()");
        assertContains(returnComplete, "faceSubsystem.verificationEnvironment()");
        assertContains(returnComplete, "FaceVerificationTicketValidator.TicketVerdict.VALID");
        assertContains(returnComplete, "new ReturnIdentity(");
        assertFalse(returnComplete.contains("customerUnlockAuthorizer"));
        assertFalse(returnComplete.contains("startDiscoveryOperation("));
        assertFalse(returnComplete.contains("unlockCoordinator.start("));
        assertFalse(returnComplete.contains("startAuthorized("));
    }

    @Test
    public void returnServiceRunsOnDaemonWorkerAndProductionHasNoFixtureFallback()
            throws Exception {
        String source = read(MAIN);
        assertContains(source, "ReturnServiceAssembly.create()");
        assertContains(source, "ReturnServiceAssembly.isLocalDemo()");
        assertContains(source, "RETURN_SERVICE_EXECUTOR");
        assertContains(source, "thread.setDaemon(true)");
        String submit = javaMethodBody(source, "submitReturnIdentity");
        assertContains(submit, "RETURN_SERVICE_EXECUTOR.execute(");
        assertContains(submit, "handler.post(");
        String palm = javaMethodBody(source, "beginReturnPalmRecognition");
        assertContains(palm, "returnLocalDemo");
        assertContains(palm, "handler.postDelayed(");
        assertContains(palm, "submitReturnIdentity(");
        assertFalse(palm.contains("DemoCredentials"));
    }

    @Test
    public void returnSerialUsesLocalTokenExactTask5PhasesAndOnePhysicalWriter()
            throws Exception {
        String source = read(MAIN);
        assertContains(source, "CustomerOperationKind.RETURN");
        String start = javaMethodBody(source, "startReturnSerialOperation");
        assertContains(start, "customerSerialTransmitter.beginAuthorizedReturn(");
        assertContains(start, "customerOperationToken");
        assertFalse(start.contains("beginAuthorizedReturn(request.operationId()"));
        String poll = javaMethodBody(source, "sendReturnDoorStatusQuery");
        assertContains(poll, "DoorStateProtocol.query(");
        assertContains(poll, "SerialWriteAttribution.doorStatus(");
        String unlock = javaMethodBody(source, "startReturnAuthorizedUnlock");
        assertContains(unlock, "CustomerSerialPhase.RETURN_UNLOCK");
        assertContains(unlock, "returnUnlockCoordinator.startAuthorized(");
        assertEquals(1, occurrences(source, "gateway.send("));
    }

    @Test
    public void ackAndDoorButtonCannotDirectlyCompleteReturn() throws Exception {
        String source = read(MAIN);
        String ack = javaMethodBody(source, "handleReturnUnlockState");
        assertContains(ack, "returnFlowController.markUnlockAccepted(");
        assertFalse(ack.contains("markStableDoorClosed("));
        assertFalse(ack.contains("acknowledgeDoorClosed("));
        String button = javaMethodBody(source, "acknowledgeReturnDoorClosed");
        assertContains(button, "returnDoorSession.acknowledgeDoorClosed(");
        assertContains(button, "requestImmediateReturnPoll()");
        assertFalse(button.contains("markStableDoorClosed("));
        assertContains(button, "returnDoorSession.isStableCloseObserved()");
        assertTrue(button.indexOf("returnDoorSession.isStableCloseObserved()")
                < button.indexOf("returnFlowController.acknowledgeDoorClosed("));
        assertContains(button, "柜门尚未稳定关闭");
    }

    @Test
    public void finalReturnUnlockWriteRevalidatesEveryAuthorityAtSendTime()
            throws Exception {
        String source = read(MAIN);
        String start = javaMethodBody(source, "startReturnAuthorizedUnlock");
        assertContains(start, "bindReturnUnlockDispatch(");
        String send = javaMethodBody(source, "sendAuthorizedReturnUnlock");
        int physicalWrite = send.indexOf("customerSerialTransmitter.send(");
        assertTrue(physicalWrite > 0);
        assertContains(send, "Looper.myLooper() != Looper.getMainLooper()");
        assertContains(send, "attemptId != returnUnlockAttemptId");
        assertContains(send, "returnUnlockDispatchGeneration != returnGeneration");
        assertContains(send, "returnUnlockDispatchOperationId != returnOperationId");
        assertContains(send, "returnUnlockDispatchCustomerToken != customerOperationToken");
        assertContains(send, "returnUnlockDispatchRequest != request");
        assertContains(send, "returnUnlockConsumed");
        assertContains(send, "Arrays.equals(request.unlockCommand(), bytes)");
        assertContains(send, "isNetworkOnline()");
        assertContains(send, "returnFlowController.validateUnlockDispatch(");
        assertContains(send, "validation != ReturnFlowController.UnlockDispatchValidation.ALLOWED");
        assertTrue(send.indexOf("isNetworkOnline()") < physicalWrite);
        assertTrue(send.indexOf("returnFlowController.validateUnlockDispatch(")
                < physicalWrite);
        assertEquals(1, occurrences(send, "customerSerialTransmitter.send("));
    }

    @Test
    public void lifecycleNeverAutoResendsConsumedReturnAuthorization() throws Exception {
        String source = read(MAIN);
        String stop = javaMethodBody(source, "onStop");
        assertContains(stop, "pauseReturnJourneyForBackground()");
        String start = javaMethodBody(source, "onStart");
        assertContains(start, "resumeReturnJourneyFromBackground()");
        String resume = javaMethodBody(source, "resumeReturnJourneyFromBackground");
        assertFalse(resume.contains("startReturnAuthorizedUnlock("));
        assertFalse(resume.contains("startAuthorized("));
        assertContains(resume, "startReturnStatusResume(");
    }

    @Test
    public void commitClearsPhysicalStateOnlyAfterAuthoritativeSuccess() throws Exception {
        String source = read(MAIN);
        String complete = javaMethodBody(source, "completeReturnAfterVerifiedClose");
        assertContains(complete, "snapshot.error() == ReturnFlowController.ErrorCode.NONE");
        assertContains(complete, "snapshot.state() == ReturnFlowModel.State.SUCCESS");
        assertContains(complete, "snapshot.state() == ReturnFlowModel.State.SELECTING");
        assertContains(complete, "clearCompletedReturnPhysicalOperation()");
        String retry = javaMethodBody(source, "retryReturnCommit");
        assertContains(retry, "clearCompletedReturnPhysicalOperation()");
        assertContains(retry, "snapshot.error() == ReturnFlowController.ErrorCode.NONE");
        String typedCommit = javaMethodBody(source, "retryPresentedReturnCommit");
        assertContains(typedCommit, "returnDoorSession.isReadyToCommit()");
        assertContains(typedCommit, "completeReturnAfterVerifiedClose()");
        assertFalse(typedCommit.contains("startReturnAuthorizedUnlock("));
    }

    @Test
    public void uncertainBaselineKeepsPollingWithoutUnlocking() throws Exception {
        String source = read(MAIN);
        String event = javaMethodBody(source, "handleReturnDoorEvent");
        assertContains(event, "ReturnDoorSession.Event.STATUS_UNCERTAIN");
        assertContains(event, "scheduleReturnDoorPoll(RETURN_POLL_BACKOFF_MILLIS)");
        int uncertain = event.indexOf("ReturnDoorSession.Event.STATUS_UNCERTAIN");
        int nextBranch = event.indexOf("ReturnDoorSession.Event.READY_TO_COMMIT");
        String uncertainBranch = event.substring(uncertain, nextBranch);
        assertFalse(uncertainBranch.contains("startReturnAuthorizedUnlock("));
    }

    @Test
    public void onlyNoLockerAndSuccessTerminalStatesAutoReturnAfterEightSeconds()
            throws Exception {
        String source = read(MAIN);
        assertContains(source, "RETURN_TERMINAL_COUNTDOWN_SECONDS = 8");
        String terminal = javaMethodBody(source, "updateReturnTerminalCountdown");
        assertContains(terminal, "presentation.autoHome()");
        assertContains(terminal, "currentReturnPresentation(current).autoHome()");
        assertContains(terminal, "finishPresentedReturnJourneyToHome(");
        assertFalse(terminal.contains("ReturnFlowModel.State.WAITING_FOR_CLOSE"));
        assertFalse(terminal.contains("ReturnFlowModel.State.COMMITTING"));
        String progress = javaMethodBody(source, "renderReturnProgress");
        assertContains(progress, "returnTerminalCountdownText(");
    }

    @Test
    public void invalidReturnScanRestoresReturnAuthenticationInsteadOfHomeCountdown()
            throws Exception {
        String source = read(MAIN);
        String finish = javaMethodBody(source, "finishPassiveCredentialInput");
        int invalid = finish.indexOf("!completion.isValidFrame()");
        int validReturn = finish.indexOf("submitReturnScannedCredential(");
        String invalidBranch = finish.substring(invalid, validReturn);
        assertContains(invalidBranch, "isReturnAuthenticationActive()");
        assertContains(invalidBranch, "showInvalidReturnCredential()");
        String append = javaMethodBody(source, "acceptPassiveCredentialCharacter");
        assertContains(append, "showInvalidReturnCredential()");
        String restore = javaMethodBody(source, "showInvalidReturnCredential");
        assertContains(restore, "returnStatusMessage");
        assertContains(restore, "renderReturnSnapshot()");
    }

    @Test
    public void serialFailureRecoveryNeverResendsConsumedAuthorization() throws Exception {
        String source = read(MAIN);
        String input = javaMethodBody(source, "buildReturnScreenInput");
        assertContains(input, ".serialFailure(returnSerialFailure)");
        assertContains(input, ".unlockConsumed(returnUnlockConsumed)");
        String retry = javaMethodBody(source, "retryPresentedReturnAction");
        assertContains(retry, "case STATUS:");
        assertContains(retry, "startReturnStatusResume()");
        assertContains(retry, "if (returnUnlockConsumed)");
        assertContains(retry, "restartAfterUnlockFailure(");
        assertTrue(retry.indexOf("if (returnUnlockConsumed)")
                < retry.indexOf("restartAfterUnlockFailure("));
        assertFalse(retry.contains("startReturnSerialOperation("));
        assertFalse(retry.contains("startReturnAuthorizedUnlock("));
    }

    @Test
    public void backgroundCommitUsesWorkerBarrierAndAuthoritativeReconcile()
            throws Exception {
        String source = read(MAIN);
        String pause = javaMethodBody(source, "pauseReturnJourneyForBackground");
        assertContains(pause, "if (returnOperationId > 0L");
        assertTrue(pause.indexOf("returnDoorSession.isReadyToCommit()")
                < pause.indexOf("returnFlowController.snapshot()"));
        assertTrue(pause.indexOf("returnDoorSession.isReadyToCommit()")
                < pause.indexOf("returnDoorSession.onDisconnected("));
        String resume = javaMethodBody(source, "resumeReturnJourneyFromBackground");
        assertContains(resume, "queueReturnCommitReconcile(");
        assertTrue(resume.indexOf("returnServiceBusy")
                < resume.indexOf("returnFlowController.snapshot()"));
        String barrier = javaMethodBody(source, "queueReturnCommitReconcile");
        assertContains(barrier, "RETURN_SERVICE_EXECUTOR.execute(");
        assertContains(barrier, "handler.post(");
        assertContains(barrier, "reconcileReturnCommitState(");
        assertContains(barrier, "generation");
        assertContains(barrier, "operationId");
        assertFalse(barrier.contains("startAuthorized("));
        assertFalse(barrier.contains("startReturnAuthorizedUnlock("));

        String reconcile = javaMethodBody(source, "reconcileReturnCommitState");
        assertContains(reconcile, "ReturnFlowModel.State.SELECTING");
        assertContains(reconcile, "ReturnFlowModel.State.SUCCESS");
        assertContains(reconcile, "ReturnFlowModel.State.COMMITTING");
        assertContains(reconcile, "ReturnFlowController.ErrorCode.COMMIT_FAILED");
        assertContains(reconcile, "returnServiceBusy = false");
        assertContains(reconcile, "clearCompletedReturnPhysicalOperation()");
        assertFalse(reconcile.contains("startReturnAuthorizedUnlock("));
    }

    @Test
    public void queryFailureIsVisibleAndRetriesSameAuthenticatedGeneration()
            throws Exception {
        String source = read(MAIN);
        String render = javaMethodBody(source, "renderReturnSnapshot");
        assertContains(render, "ReturnScreenResolver.resolve(");
        assertContains(render, "presentation.surface()");
        String form = javaMethodBody(source, "submitReturnFormCredential");
        assertContains(form, "isReturnQueryRetryable()");
        assertContains(form, "retryReturnQuery()");
        String scan = javaMethodBody(source, "submitReturnScannedCredential");
        assertContains(scan, "isReturnQueryRetryable()");
        assertContains(scan, "retryReturnQuery()");
        String retry = javaMethodBody(source, "retryReturnQuery");
        assertContains(retry, "RETURN_SERVICE_EXECUTOR.execute(");
        assertContains(retry, "returnFlowController.retryQuery(");
        assertContains(retry, "handler.post(");
    }

    @Test
    public void authorizationFailureRetriesSameOperationBeforeAnySerialWork()
            throws Exception {
        String source = read(MAIN);
        String confirm = javaMethodBody(source, "confirmReturnLocker");
        assertContains(confirm, "ReturnFlowModel.State.CONFIRMING");
        assertContains(confirm, "retryReturnAuthorization(");
        assertTrue(confirm.indexOf("retryReturnAuthorization(")
                < confirm.indexOf("nextReturnOperationId()"));
        String retry = javaMethodBody(source, "retryReturnAuthorization");
        assertContains(retry, "returnFlowController.retryAuthorization(");
        assertContains(retry, "generation, operationId");
        assertContains(retry, "authorizedRequestForStatusBaseline(");
        assertContains(retry, "startReturnSerialOperation(request)");
        assertFalse(retry.contains("nextReturnOperationId()"));
    }

    @Test
    public void waitingErrorsRenderBeforeWaitingAndRetryNeverUnlocks() throws Exception {
        String source = read(MAIN);
        String input = javaMethodBody(source, "buildReturnScreenInput");
        assertContains(input, ".serialFailure(returnSerialFailure)");
        assertContains(input, ".commitDeferred(returnCommitDeferred)");
        String retry = javaMethodBody(source, "retryPresentedReturnAction");
        assertContains(retry, "case STATUS:");
        assertContains(retry, "startReturnStatusResume()");
        assertFalse(retry.contains("startReturnAuthorizedUnlock("));
        assertFalse(retry.contains("CustomerSerialPhase.RETURN_UNLOCK"));
        String statusResume = javaMethodBody(source, "startReturnStatusResume");
        assertFalse(statusResume.contains("resumeStatusMonitoring("));
        String event = javaMethodBody(source, "handleReturnDoorEvent");
        assertContains(event, "resumeStatusMonitoring(");
    }

    @Test
    public void offlineReadyToCommitRendersRetryAndNeverRestartsUnlock() throws Exception {
        String source = read(MAIN);
        String complete = javaMethodBody(source, "completeReturnAfterVerifiedClose");
        assertContains(complete, "returnCommitDeferred = true");
        String input = javaMethodBody(source, "buildReturnScreenInput");
        assertContains(input, ".commitDeferred(returnCommitDeferred)");
        String retry = javaMethodBody(source, "retryPresentedReturnAction");
        assertContains(retry, "case COMMIT:");
        assertContains(retry, "retryPresentedReturnCommit(");
        assertFalse(retry.contains("startReturnAuthorizedUnlock("));
    }

    private static String read(String relative) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relative);
        if (!Files.exists(file)) {
            file = root.resolve("work/smart-locker-serial-test-v15").resolve(relative);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String javaMethodBody(String source, String methodName) {
        int name = -1;
        int search = 0;
        while ((search = source.indexOf(methodName + "(", search)) >= 0) {
            int lineStart = source.lastIndexOf('\n', search) + 1;
            String declarationPrefix = source.substring(lineStart, search).trim();
            if (declarationPrefix.startsWith("private ")
                    || declarationPrefix.startsWith("protected ")
                    || declarationPrefix.startsWith("public ")) {
                name = search;
                break;
            }
            search += methodName.length() + 1;
        }
        if (name < 0) fail("missing method: " + methodName);
        int open = source.indexOf('{', name);
        if (open < 0) fail("missing method body: " + methodName);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    private static void assertContains(String text, String marker) {
        assertTrue("missing: " + marker, text.contains(marker));
    }
}
