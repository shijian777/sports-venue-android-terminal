package com.codex.lockertest.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IdCardIntegrationSourceTest {
    @Test
    public void homeHasNoCardOrQrDestinationAndShowsOnlyPassiveScanStatus() throws IOException {
        String home = source("ui", "ZipHomeView.java");
        assertFalse(home.contains("onIdCardSelected"));
        assertFalse(home.contains("addIdCardButton"));
        assertTrue(home.contains("请直接刷手环或扫描二维码"));
        assertTrue(home.contains("showCredentialReading"));
        assertTrue(home.contains("showInvalidCredential"));
    }

    @Test
    public void activityCapturesOnlyScanKeysAndSuccessStartsDiscoveryNotUnlock() throws IOException {
        String main = source(".", "MainActivity.java");
        String dispatch = slice(main,
                "    public boolean dispatchKeyEvent(KeyEvent event)",
                "    private void returnHome(");
        assertTrue(dispatch.contains("isPassiveCredentialCaptureActive()"));
        assertTrue(dispatch.contains("event.getRepeatCount() == 0"));
        assertTrue(dispatch.contains("KeyEvent.ACTION_DOWN"));
        assertTrue(main.contains("KeyEvent.KEYCODE_NUMPAD_ENTER"));
        assertTrue(main.contains("KeyEvent.KEYCODE_TAB"));
        assertTrue(main.contains("event.getUnicodeChar()"));

        String completion = slice(main,
                "    private void finishPassiveCredentialInput(",
                "    private void resetPassiveCredentialCapture()");
        assertTrue(completion.contains("flow.submitScannedCredential("));
        assertTrue(completion.contains("startDiscoveryOperation();"));
        assertFalse(completion.contains("unlockCoordinator.start("));
        assertFalse(completion.contains("rawCredential +"));
        assertFalse(completion.contains("DemoCredentials"));
        assertTrue(completion.contains("flow.pendingCredentialMethod()"));
    }

    @Test
    public void commonScannerFlowUsesOneOpaquePolicyAdmissionAndNoDemoParser() throws IOException {
        String flow = source("ui", "KioskFlowModel.java");
        String submit = slice(flow,
                "    public boolean submitScannedCredential(",
                "    public boolean beginFaceRecognition()");

        assertEquals(1, occurrences(submit,
                "credentialPolicy.admit(UnlockMethod.ID_CARD, rawValue)"));
        assertFalse(submit.contains("DemoCredentials"));
        assertFalse(submit.contains("rawValue.length"));
        assertFalse(submit.contains("startsWith"));
        assertFalse(submit.contains("contains(\"~\")"));
        assertFalse(submit.contains("contains(\"-\")"));
        assertTrue(submit.contains("pendingCredentialMethod = admission.method();"));
    }

    @Test
    public void commonLayoutSourceHasNoLegacyFallback() throws IOException {
        java.nio.file.Path commonLayout = Paths.get(
                "app", "src", "main", "java", "com", "codex", "lockertest", "layout");
        assertFalse(Files.exists(commonLayout.resolve("LegacyV6LockerLayoutSource.java")));
        String selection = source("ui", "LockerSelectionModel.java");
        assertFalse(selection.contains("LegacyV6LockerLayoutSource"));
        assertTrue(selection.contains("layoutPolicy.layoutForDiscovery(discoveredZones)"));
    }

    @Test
    public void activityBuildsRuntimeInOnCreateAndPassesPoliciesToFlowAndUi() throws IOException {
        String main = source(".", "MainActivity.java");
        String onCreate = slice(main,
                "    protected void onCreate(Bundle savedInstanceState)",
                "    @Override\n    protected void onStart()");

        assertTrue(onCreate.contains(
                "RuntimeAssembly.create(getApplicationContext())"));
        assertTrue(onCreate.contains(
                "new KioskFlowModel(credentialPolicy, runtime.layoutPolicy(), terminalReadinessSource)"));
        assertTrue(onCreate.contains(
                "customerUnlockAuthorizer = runtime.unlockAuthorizer()"));
        assertTrue(main.contains(
                "this, credentialPolicy, featureAvailability, bootstrapHomePresentation"));
        assertTrue(main.contains("bootstrapUiSessionGate.restartBootstrap()"));

        String home = source("ui", "HomeView.java");
        String unlock = source("ui", "UnlockPageModel.java");
        assertFalse(home.contains("DemoFeatureFlags"));
        assertFalse(unlock.contains("DemoFeatureFlags"));
        assertTrue(home.contains(
                "HomeView(Context context, FeatureAvailability availability)"));
        assertFalse(unlock.contains("UnlockPageModel(UnlockMethod method)"));
    }

    @Test
    public void activityDoesNotNavigateToASeparateScanPageAndLifecycleClearsCapture() throws IOException {
        String main = source(".", "MainActivity.java");
        assertFalse(main.contains("IdCardScanView"));
        assertFalse(main.contains("renderIdCardScan"));
        assertFalse(main.contains("openIdCardScan"));
        String stop = slice(main,
                "    protected void onStop()",
                "    @Override\n    public void onWindowFocusChanged");
        assertTrue(stop.contains("resetPassiveCredentialCapture();"));
        assertTrue(stop.contains("idCardEventDrain.clear();"));
        assertFalse(stop.contains("removeCallbacksAndMessages"));
    }

    @Test
    public void invalidCredentialOffersAnEightSecondManualRecoveryButton() throws IOException {
        String home = source("ui", "ZipHomeView.java");

        assertTrue(home.contains("showInvalidCredential(int secondsRemaining)"));
        assertTrue(home.contains("重新识别（"));
        assertTrue(home.contains("setCredentialRecoveryListener"));
        assertTrue(home.contains("credentialRecoveryButton.setOnClickListener"));
    }

    @Test
    public void activityRestartsCountdownAndCancelsItOnNewInputOrLifecycleReset() throws IOException {
        String main = source(".", "MainActivity.java");
        String invalid = slice(main,
                "    private void showInvalidCredentialWithAutoReset()",
                "    private void resetPassiveCredentialCapture()");
        String reset = slice(main,
                "    private void resetPassiveCredentialCapture()",
                "    private void resetConsumedIdCardKey()");
        String append = slice(main,
                "    private void acceptPassiveCredentialCharacter(",
                "    private void finishPassiveCredentialInputNow(");

        assertTrue(main.contains("INVALID_CREDENTIAL_RECOVERY_SECONDS = 8"));
        assertTrue(invalid.contains("credentialRecoveryCountdown.start();"));
        assertTrue(invalid.contains("handler.postDelayed"));
        assertTrue(invalid.contains("homeView != expectedHome"));
        assertTrue(invalid.contains("flow.screen() != KioskFlowModel.Screen.HOME"));
        assertTrue(reset.contains("cancelInvalidCredentialRecovery();"));
        assertTrue(append.contains("cancelInvalidCredentialRecovery();"));
    }

    private static String source(String directory, String file) throws IOException {
        java.nio.file.Path root = Paths.get(
                "app", "src", "main", "java", "com", "codex", "lockertest");
        return new String(Files.readAllBytes(root.resolve(directory).resolve(file)),
                StandardCharsets.UTF_8);
    }

    private static String slice(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
