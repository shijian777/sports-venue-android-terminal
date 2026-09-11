package com.codex.lockertest.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Android-free source contract for native home interactions over ZIP screens 1-4. */
public final class ZipHomeInteractionSourceTest {
    @Test
    public void homeUsesPixelShellBrandAndExactAbsoluteNativeRegions() throws Exception {
        String source = home();
        assertContains(source, "new ZipPixelShell(context, ZipScreenAsset.HOME_WAITING)");
        assertContains(source,
                "pixelShell.setOnSafeHomeRequested(this::requestCredentialRecovery)");
        assertContains(source, "ZipBrand.HOME_TITLE");
        assertContains(source, "place(content, phoneField, 435, 101, 439, 63)");
        assertContains(source, "place(content, passwordField, 435, 197, 439, 63)");
        assertContains(source, "place(content, keypad, 436, 291, 436, 334)");
        assertContains(source, "place(content, confirmButton, 436, 657, 438, 60)");
        assertContains(source, "place(content, rightInteractions, 900, 99, 336, 621)");
        assertFalse(source.contains("weighted(context"));
        assertFalse(source.contains("new ZipKioskShell("));
    }

    @Test
    public void modalStatesAreOrthogonalAndBlockUnderlyingTouchAndAccessibility()
            throws Exception {
        String source = home();
        assertContains(source, "enum PassiveCredentialState");
        assertContains(source, "private PassiveCredentialState passiveCredentialState");
        assertContains(source, "private boolean validationErrorVisible");
        assertContains(source, "private void applyHomePresentation()");
        assertContains(source, "ZipCustomerScreenRouter.State.HOME_CREDENTIAL_ERROR");
        assertContains(source, "ZipCustomerScreenRouter.State.HOME_UNREGISTERED_COUNTDOWN");
        assertContains(source, "ZipCustomerScreenRouter.State.HOME_READING_CREDENTIAL");
        assertContains(source, "ZipCustomerScreenRouter.State.HOME_WAITING");
        assertContains(source, "pixelShell.setScreenAsset(ZipScreenAsset.HOME_WAITING)");
        assertContains(source, "setUnderlyingInteractionsEnabled(!modal)");
        assertContains(source, "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS");
        assertContains(source, "place(overlay, modalInterceptionLayer, 0, 0, 1280, 800)");
        assertContains(source, "modalInterceptionLayer.bringToFront()");
        assertContains(source, "modalActionButton.bringToFront()");

        String validation = slice(source, "public void showValidationError(",
                "public void clearInputs()");
        assertFalse(validation.contains("credentialRecoveryListener"));
        assertFalse(validation.contains("showCredentialWaiting"));
    }

    @Test
    public void allModalGeometryAndSingleActionsMatchTheDesignCanvas() throws Exception {
        String source = home();
        assertContains(source, "place(overlay, readingErrorModal, 330, 225, 620, 400)");
        assertContains(source, "place(overlay, unregisteredModal, 330, 225, 620, 400)");
        assertContains(source, "place(overlay, countdownView, 540, 427, 200, 63)");
        assertContains(source, "place(overlay, modalActionButton, 525, 538, 230, 48)");
        assertContains(source, "place(overlay, recoveryActionButton, 450, 538, 380, 48)");
        assertContains(source, "addModalCopy(readingErrorModal, readingErrorMessage)");
    }

    @Test
    public void palmEnrollmentButtonRoutesWithoutGrantingAccess()
            throws Exception {
        String source = home();
        assertContains(source, "掌纹录入");
        assertContains(source, "finalHomeActions.palmEnrollmentVisible()");
        assertContains(source, "current.onEnrollmentRequested()");
        assertFalse(source.contains("ENROLLMENT_CHOICE"));
        assertFalse(source.contains("录入成功"));
        assertFalse(source.contains("已登记"));
    }

    @Test
    public void bakedClockAndUsageAreCoveredByLiveHonestLocalValues() throws Exception {
        String source = home();
        assertContains(source, "new SimpleDateFormat(\"yyyy/MM/dd  HH:mm\"");
        assertContains(source, "clockTicker.run()");
        assertContains(source, "handler.removeCallbacks(clockTicker)");
        assertContains(source, "bootstrapPresentation.usageText()");
        assertContains(source, "bootstrapPresentation.statusMessage()");
        assertContains(source, "bootstrapPresentation.retryAvailable()");
        assertFalse(source.contains("registerCustomerAction(bootstrapRetryButton)"));
        assertFalse(source.contains("2025/12/25"));
        assertFalse(source.contains("24/50"));

        String compatibility = read(
                "app/src/main/java/com/codex/lockertest/ui/HomeView.java");
        assertContains(compatibility, "ZipBrand.HOME_TITLE");
        assertContains(compatibility, "ZipBrand.FOOTER");
        assertContains(compatibility, "ZipBrand.VERSION");
    }

    @Test
    public void mainKeepsPassiveCaptureAndStartsCameraOnlyFromFaceSelection()
            throws Exception {
        String source = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String homeFactory = methodBody(source, "createHomeView");
        assertContains(homeFactory, "void onFaceRequested()");
        assertContains(homeFactory, "beginFaceRecognition()");
        assertFalse(homeFactory.contains("startFaceController("));
        assertFalse(methodBody(source, "submitCredential").contains("beginFaceRecognition("));
        assertFalse(methodBody(source, "finishPassiveCredentialInput")
                .contains("beginFaceRecognition("));
        assertContains(methodBody(source, "renderHome"), "resetPassiveCredentialCapture()");
        assertContains(source, "idCardEventDrain");
    }

    @Test
    public void faceScreensUseOnePersistentSurfaceAndExactNativeLayers()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java");
        assertContains(source, "new ZipPixelShell(context, ZipScreenAsset.FACE_PREPARING)");
        assertContains(source, "place(content, preview, 270, 185, 740, 410)");
        assertContains(source, "place(content, previewOverlay, 270, 185, 740, 410)");
        assertContains(source, "place(content, statusPanel, 405, 500, 470, 74)");
        assertContains(source, "place(content, statusEmphasis, 405, 570, 470, 5)");
        assertContains(source, "place(overlay, errorModal, 390, 250, 500, 300)");
        assertContains(source, "place(overlay, retryButton, 465, 480, 150, 44)");
        assertContains(source, "place(overlay, backButton, 665, 480, 150, 44)");
        assertContains(source, "place(overlay, homeButton, 565, 480, 150, 44)");
        assertContains(source, "place(overlay, topReturnButton, 1050, 110, 176, 38)");
        assertTrue(occurrences(source, "new SurfaceView(context)") == 1);
        assertFalse(source.contains("removeView(preview"));
        assertFalse(source.contains("setZOrderOnTop"));
    }

    @Test
    public void faceStateRoutingControlsPreviewAndRetryWithoutFakePermanentActions()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java");
        assertContains(source, "State.FACE_PREPARING");
        assertContains(source, "State.FACE_DETECTING");
        assertContains(source, "State.FACE_UPLOADING");
        assertContains(source, "State.FACE_CAMERA_PERMISSION_TEMPORARY");
        assertContains(source, "State.FACE_CAMERA_PERMISSION_PERMANENT");
        assertContains(source, "State.FACE_RETRYABLE_FAILURE");
        assertContains(source, "State.FACE_COMPONENT_UNAVAILABLE");
        assertContains(source, "preview.setVisibility(active ? View.VISIBLE : View.INVISIBLE)");
        assertContains(source, "retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE)");
        assertContains(source, "backButton.setVisibility(showBack ? View.VISIBLE : View.GONE)");
        assertContains(source, "homeButton.setVisibility(showHome ? View.VISIBLE : View.GONE)");
        assertContains(source, "public void showPermissionDenied(boolean permanentlyDenied)");
        assertFalse(source.contains("permanentlyDenied ? View.VISIBLE"));
        String permanentPermission = methodBody(source, "showPermissionDenied");
        assertContains(permanentPermission, "homeButton.setText(\"返回首页\")");
        String terminalFailure = methodBody(source, "showFailure");
        assertContains(terminalFailure, "if (!retryable)");
        assertContains(terminalFailure, "homeButton.setText(\"返回\")");
    }

    @Test
    public void palmActionOnlyChangesTwelveToThirteenAndOffersSafeReturn()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/UnavailableMethodView.java");
        assertContains(source, "new ZipPixelShell(context, ZipScreenAsset.PALM_GUIDE)");
        assertContains(source, "State.PALM_DEVICE_UNAVAILABLE");
        assertContains(source, "place(overlay, topReturnButton, 1050, 110, 176, 38)");
        assertContains(source, "place(overlay, startButton, 520, 621, 240, 59)");
        assertContains(source, "place(overlay, safeHomeButton, 565, 478, 150, 44)");
        assertContains(source, "current.onShowUnavailableAgain()");
        assertFalse(source.contains("IllustrationView"));
        assertFalse(source.contains("ZipPromptOverlay"));
        assertFalse(source.contains("showUnavailablePrompt();\n    }"));
        for (String forbidden : new String[] {
                "FaceSubsystem", "SerialGateway", "unlockCoordinator",
                "startDiscoveryOperation", ".send("
        }) {
            assertFalse("palm path must remain presentation-only: " + forbidden,
                    source.contains(forbidden));
        }
    }

    private static String home() throws IOException {
        return read("app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java");
    }

    private static String methodBody(String source, String methodName) {
        java.util.regex.Matcher declaration = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)\\s+"
                        + "(?:(?:static|final|synchronized)\\s+)*"
                        + "[A-Za-z0-9_$.<>?,\\[\\] ]+\\s+"
                        + java.util.regex.Pattern.quote(methodName) + "\\s*\\(")
                .matcher(source);
        if (!declaration.find()) fail("missing method: " + methodName);
        int open = source.indexOf('{', declaration.end());
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static String slice(String source, String marker, String next) {
        int start = source.indexOf(marker);
        if (start < 0) fail("missing marker: " + marker);
        int end = source.indexOf(next, start + marker.length());
        if (end < 0) fail("missing next marker: " + next);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(token, at)) >= 0) {
            count++;
            at += token.length();
        }
        return count;
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
