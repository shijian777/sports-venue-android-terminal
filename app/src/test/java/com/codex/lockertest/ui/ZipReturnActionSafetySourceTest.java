package com.codex.lockertest.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Android-boundary contracts for native return actions and exact typed retry dispatch. */
public final class ZipReturnActionSafetySourceTest {
    private static final String UI = "app/src/main/java/com/codex/lockertest/ui/";
    private static final String MAIN =
            "app/src/main/java/com/codex/lockertest/MainActivity.java";

    @Test
    public void allReturnViewsUsePixelAssetsAndOpaqueNativeDesignCoordinates()
            throws Exception {
        String auth = read(UI + "ReturnAuthView.java");
        String locker = read(UI + "ReturnLockerView.java");
        String progress = read(UI + "ReturnProgressView.java");

        for (String source : new String[] {auth, locker, progress}) {
            assertContains(source, "new ZipPixelShell(");
            assertContains(source, "setScreenAsset(presentation.asset())");
            assertContains(source, "UiKit.roundedSolid(");
            assertContains(source, "setClickable(enabled)");
            assertContains(source, "IMPORTANT_FOR_ACCESSIBILITY_YES");
            assertFalse(source.contains("new ZipKioskShell("));
            assertFalse(source.contains("setBackgroundColor(Color.TRANSPARENT)"));
        }

        assertContains(auth, "place(content, authCard, 386, 212, 510, 250)");
        assertContains(auth, "place(content, passwordField, 481, 332, 320, 48)");
        assertContains(auth, "place(content, submitButton, 551, 398, 180, 42)");
        assertContains(locker, "place(content, listCard, 270, 190, 740, 360)");
        assertContains(locker, "ROW_Y = {272, 327, 382, 437}");
        assertContains(locker, "place(content, confirmButton, 550, 495, 180, 42)");
        assertContains(progress, "place(content, promptCard, 355, 225, 570, 330)");
        assertContains(progress, "place(content, leftButton, 470, 480, 150, 46)");
        assertContains(progress, "place(content, rightButton, 660, 480, 150, 46)");
        assertContains(progress, "place(content, centerButton, 565, 480, 150, 46)");
        for (String source : new String[] {auth, locker, progress}) {
            assertContains(source, "place(shell.overlayLayer(), safeReturnButton, "
                    + "1050, 110, 176, 38)");
        }
    }

    @Test
    public void lockerRowsStayBoundToSnapshotAndProcessingBlocksTouchAndAccessibility()
            throws Exception {
        String locker = read(UI + "ReturnLockerView.java");
        String main = read(MAIN);

        assertContains(locker, "Collections.unmodifiableList(new ArrayList<>(lockers))");
        assertContains(locker, "visibleLockers.contains(selected)");
        assertContains(locker, "presentation.canSelect()");
        assertContains(locker, "presentation.canConfirmLocker()");
        assertContains(locker, "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS");
        assertFalse(locker.contains("new ReturnLocker("));
        assertFalse(locker.contains("\"A1\"") || locker.contains("\"A2\""));
        assertContains(main, "snapshot.remainingLockers()");
        assertContains(main, "presentation.canConfirmLocker()");
        assertContains(main, "returnServiceBusy");
    }

    @Test
    public void mainRoutesOnlyTypedStateAndDispatchesEveryRetryKindExplicitly()
            throws Exception {
        String main = read(MAIN);
        String render = javaMethodBody(main, "renderReturnSnapshot");
        String retry = javaMethodBody(main, "retryPresentedReturnAction");

        assertContains(render, "ReturnScreenResolver.resolve(");
        assertContains(render, "presentation.surface()");
        assertFalse(render.contains("returnStatusMessage.contains("));
        assertContains(retry, "switch (presentation.retryKind())");
        assertContains(retry, "case AUTH:");
        assertContains(retry, "restartReturnIdentityEntry()");
        assertContains(retry, "case QUERY:");
        assertContains(retry, "retryReturnQuery()");
        assertContains(retry, "restartAfterUnlockFailure(");
        assertContains(retry, "case AUTHORIZATION:");
        assertContains(retry, "retryReturnAuthorization(");
        assertContains(retry, "case STATUS:");
        assertContains(retry, "startReturnStatusResume()");
        assertContains(retry, "case COMMIT:");
        assertContains(retry, "retryPresentedReturnCommit(");
        assertContains(retry, "case NONE:");
        assertFalse(retry.contains("retryReturnJourney("));
        assertFalse(retry.contains("startReturnAuthorizedUnlock("));
        assertFalse(retry.contains("CustomerSerialPhase.RETURN_UNLOCK"));
    }

    @Test
    public void consumedUnlockBackAndHomeAreRevalidatedAtTheMainBoundary()
            throws Exception {
        String main = read(MAIN);
        String cancel = javaMethodBody(main, "cancelReturnJourneyFromUser");
        String home = javaMethodBody(main, "finishPresentedReturnJourneyToHome");
        String retry = javaMethodBody(main, "retryPresentedReturnAction");

        assertContains(cancel, "presentation.canBack()");
        assertContains(cancel, "presentation.canCancel()");
        assertContains(cancel, "returnUnlockConsumed");
        assertContains(home, "presentation.canHome()");
        assertContains(home, "returnUnlockConsumed");
        assertContains(retry, "returnUnlockConsumed");
        assertContains(retry, "ReturnFlowController.ErrorCode.UNLOCK_FAILED");
        assertTrue(retry.indexOf("returnUnlockConsumed")
                < retry.indexOf("restartAfterUnlockFailure("));
    }

    @Test
    public void closeConfirmationOnlyAcknowledgesAndRequestsFreshHardwarePoll()
            throws Exception {
        String main = read(MAIN);
        String progressRender = javaMethodBody(main, "renderReturnProgress");
        String acknowledge = javaMethodBody(main, "acknowledgeReturnDoorClosed");

        assertContains(progressRender, "acknowledgeReturnDoorClosed()");
        assertContains(acknowledge, "returnDoorSession.isStableCloseObserved()");
        assertContains(acknowledge, "returnDoorSession.acknowledgeDoorClosed(");
        assertContains(acknowledge, "requestImmediateReturnPoll()");
        assertFalse(acknowledge.contains("markStableDoorClosed("));
        assertFalse(acknowledge.contains("completeReturnAfterVerifiedClose("));
    }

    @Test
    public void terminalCountdownRemainsSingleShotAndLifecycleCleansIt()
            throws Exception {
        String main = read(MAIN);
        String update = javaMethodBody(main, "updateReturnTerminalCountdown");
        String cancel = javaMethodBody(main, "cancelReturnTerminalCountdown");
        String stop = javaMethodBody(main, "pauseReturnJourneyForBackground");
        String finish = javaMethodBody(main, "finishReturnJourneyToHome");

        assertContains(main, "RETURN_TERMINAL_COUNTDOWN_SECONDS = 8");
        assertContains(update, "presentation.autoHome()");
        assertContains(update, "if (returnTerminalTask != null)");
        assertContains(update, "handler.postDelayed(");
        assertContains(cancel, "handler.removeCallbacks(returnTerminalTask)");
        assertContains(stop, "cancelReturnTimers()");
        assertContains(finish, "cancelReturnTimers()");
    }

    @Test
    public void passiveReturnCredentialReadingUsesTypedBusyPageAndClearsBeforeSubmit()
            throws Exception {
        String main = read(MAIN);
        String append = javaMethodBody(main, "acceptPassiveCredentialCharacter");
        String finish = javaMethodBody(main, "finishPassiveCredentialInput");

        assertContains(append, "returnCredentialReading = true");
        assertContains(append, "renderReturnSnapshot()");
        assertFalse(append.contains("returnAuthView.showLoading("));
        assertContains(finish, "returnCredentialReading = false");
        assertTrue(finish.indexOf("returnCredentialReading = false")
                < finish.indexOf("submitReturnScannedCredential("));
    }

    @Test
    public void everyPreUnlockOfflineBoundaryPublishesTypedAuthorizationFailure()
            throws Exception {
        String main = read(MAIN);
        String confirm = javaMethodBody(main, "confirmReturnLocker");
        String retryAuthorization = javaMethodBody(main, "retryReturnAuthorization");
        String startUnlock = javaMethodBody(main, "startReturnAuthorizedUnlock");
        String fail = javaMethodBody(
                main, "failReturnAuthorizationBeforeUnlock");

        assertEquals(2, occurrences(
                confirm, "failReturnAuthorizationBeforeUnlock("));
        assertEquals(1, occurrences(
                retryAuthorization, "failReturnAuthorizationBeforeUnlock("));
        assertEquals(1, occurrences(
                startUnlock, "failReturnAuthorizationBeforeUnlock("));
        assertContains(fail, "markAuthorizationUnavailableBeforeUnlock(");
        assertContains(fail, "returnAuthorizedRequest = null");
        assertFalse(fail.contains("RETURN_UNLOCK"));
        assertFalse(fail.contains("startAuthorized("));
        assertFalse(fail.contains("send("));
    }

    @Test
    public void returnPhaseTransitionRejectionBecomesTypedFailureBeforeCoordinatorStart()
            throws Exception {
        String main = read(MAIN);
        String startUnlock = javaMethodBody(main, "startReturnAuthorizedUnlock");
        String fail = javaMethodBody(main, "failReturnUnlockBeforeWrite");

        int transition = startUnlock.indexOf("transitionAuthorizedReturn(");
        int failure = startUnlock.indexOf(
                "failReturnUnlockBeforeWrite(", transition);
        int coordinator = startUnlock.indexOf(
                "returnUnlockCoordinator.startAuthorized(");
        assertTrue(transition >= 0 && transition < failure && failure < coordinator);
        assertContains(fail, "returnFlowController.markUnlockFailed(");
        assertContains(fail, "returnUnlockConsumed");
        assertFalse(fail.contains("sendAuthorizedReturnUnlock("));
        assertFalse(fail.contains("startAuthorized("));
        assertFalse(fail.contains("CustomerSerialTransmitter.send("));
    }

    @Test
    public void returnCoordinatorStartRejectionBecomesTypedFailureWithoutAWrite()
            throws Exception {
        String main = read(MAIN);
        String startUnlock = javaMethodBody(main, "startReturnAuthorizedUnlock");
        String fail = javaMethodBody(main, "failReturnUnlockBeforeWrite");

        int coordinator = startUnlock.indexOf(
                "returnUnlockCoordinator.startAuthorized(");
        int rejected = startUnlock.indexOf("if (attemptId <= 0L)", coordinator);
        int failure = startUnlock.indexOf(
                "failReturnUnlockBeforeWrite(", rejected);
        assertTrue(coordinator >= 0 && coordinator < rejected && rejected < failure);
        assertEquals(2, occurrences(startUnlock, "failReturnUnlockBeforeWrite("));
        assertContains(fail, "returnFlowController.markUnlockFailed(");
        assertContains(fail, "returnUnlockConsumed");
        assertFalse(fail.contains("sendAuthorizedReturnUnlock("));
        assertFalse(fail.contains("startAuthorized("));
        assertFalse(fail.contains("CustomerSerialTransmitter.send("));
    }

    @Test
    public void finalExpiredWriterGatePreservesAuthorizationRetryAndDispatcherCannotUnlock()
            throws Exception {
        String main = read(MAIN);
        String writer = javaMethodBody(main, "sendAuthorizedReturnUnlock");
        String failure = javaMethodBody(main, "handleReturnUnlockState");
        String retry = javaMethodBody(main, "retryPresentedReturnAction");

        assertContains(writer,
                "UnlockDispatchValidation.AUTHORIZATION_EXPIRED");
        assertContains(writer,
                "markUnlockAuthorizationExpiredBeforeWrite(");
        int validationFailure = writer.indexOf("if (validation !=");
        int preserveReason = writer.indexOf(
                "markUnlockAuthorizationExpiredBeforeWrite(", validationFailure);
        int rejected = writer.indexOf("return false", validationFailure);
        assertTrue(validationFailure >= 0
                && validationFailure < preserveReason
                && preserveReason < rejected);
        assertContains(failure, "markUnlockFailed(");
        assertContains(retry, "case AUTHORIZATION:");
        assertContains(retry, "retryReturnAuthorization(");
        assertFalse(retry.contains("sendAuthorizedReturnUnlock("));
        assertFalse(retry.contains("startReturnAuthorizedUnlock("));
        assertFalse(retry.contains("CustomerSerialPhase.RETURN_UNLOCK"));
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }

    private static String javaMethodBody(String source, String methodName) {
        int name = -1;
        int search = 0;
        while ((search = source.indexOf(methodName + "(", search)) >= 0) {
            int lineStart = source.lastIndexOf('\n', search) + 1;
            String prefix = source.substring(lineStart, search).trim();
            if (prefix.startsWith("private ") || prefix.startsWith("protected ")
                    || prefix.startsWith("public ")) {
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

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
