package com.codex.lockertest.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source boundary checks for native controls over the ZIP administrator artwork. */
public final class ZipAdminActionSafetySourceTest {
    @Test
    public void pinAndFunctionPagesUsePixelAssetsAndFourRealCallbacks() throws Exception {
        String pin = readMain("ui/AdminPinOverlay.java");
        String functions = readMain("ui/AdminFunctionOverlay.java");
        assertContains(pin, "ZipScreenAsset.ADMIN_PIN_ENTRY");
        assertContains(pin, "ZipAdminScreenRouter.PinState.ERROR");
        assertContains(pin, "ZipAdminScreenRouter.assetForPin(");
        assertContains(pin, "PasswordTransformationMethod");
        assertContains(pin, "new InputFilter.LengthFilter(requiredLength)");
        assertContains(pin, "pinInput.setSaveEnabled(false)");
        assertContains(pin, "pinInput.setSaveFromParentEnabled(false)");
        assertContains(pin, "pinInput.getText().clear()");
        assertContains(pin, "adminPolicy.matches(candidate)");
        assertContains(pin, "Arrays.fill(candidate, '\\0')");
        assertFalse(pin.contains("DemoCredentials"));
        assertContains(pin, "ZipKioskShell.unit(context, 510)");
        assertContains(pin, "ZipKioskShell.unit(context, 250)");
        assertFalse(pin.contains("SharedPreferences"));
        assertFalse(pin.contains("android.util.Log"));

        assertContains(functions, "ZipScreenAsset.ADMIN_FUNCTIONS");
        assertContains(functions, "void onSerialRequested()");
        assertContains(functions, "void onFaceSdkRequested()");
        assertContains(functions, "void onEnrollmentRequested()");
        assertContains(functions, "void onHomeRequested()");
        assertContains(functions, "current.onSerialRequested()");
        assertContains(functions, "current.onFaceSdkRequested()");
        assertContains(functions, "current.onEnrollmentRequested()");
        assertContains(functions, "current.onHomeRequested()");
        assertContains(functions, "ADMIN_PANEL_LEFT = 20");
        assertContains(functions, "ADMIN_PANEL_TOP = 105");
        assertContains(functions, "ADMIN_PANEL_RIGHT = 1260");
        assertContains(functions, "ADMIN_PANEL_BOTTOM = 710");
        assertContains(functions, "nativeAdminPanel.setBackgroundColor(Color.WHITE)");
        assertContains(functions, "cardParams.leftMargin = ZipKioskShell.unit(context, 322)");
        assertContains(functions, "cardParams.topMargin = ZipKioskShell.unit(context, 185)");
        assertContains(functions, "context, 60, 115)");
        assertContains(functions, "context, 340, 115)");
        assertContains(functions, "context, 60, 205)");
        assertContains(functions, "context, 340, 205)");
        assertFalse(functions.contains("startActivity"));
    }

    @Test
    public void serialPageUsesTypedPhaseExactRequestAndPersistentOwner() throws Exception {
        String serial = readMain("AdminSerialActivity.java");
        assertContains(serial, "ZipScreenAsset.ADMIN_SERIAL_DISCONNECTED");
        assertContains(serial, "ZipAdminScreenRouter.assetForSerial(");
        assertContains(serial, "ZipAdminScreenRouter.SerialRequestGate<");
        assertContains(serial, "ProcessSerialGatewayOwner.Lease<SerialGateway>");
        assertContains(serial, "SerialRequestGate.TIMEOUT_MILLIS");
        assertContains(serial, "LockerResponseDetector.Result.FAILURE");
        assertContains(serial, "LockerResponseDetector.Result.SUCCESS");
        assertContains(serial, "SERIAL_GATEWAY_OWNER.isCurrent(expectedLease)");
        assertContains(serial, "SERIAL_CONNECTION_POLICY.onManualCloseAccepted()");
        assertContains(serial, "private static final ZipAdminScreenRouter.SerialA1RetryFence");
        assertContains(serial, "A1_RETRY_FENCE.onAmbiguousTerminal()");
        assertContains(serial, "A1_RETRY_FENCE.observe(phase)");
        assertContains(serial, "!A1_RETRY_FENCE.canBeginA1()");
        assertContains(serial, "ZipAdminScreenRouter.classifyOpenDispatch(");
        assertContains(serial, "OpenDispatchResult.OPEN_REJECTED");
        assertContains(serial, "ZipKioskShell.unit(this,");
        assertFalse(serial.contains("detail.startsWith("));

        String stop = methodSlice(serial, "protected void onStop()",
                "protected void onDestroy()");
        assertContains(stop, "detachAdminGateway()");
        assertFalse(stop.contains("closePort("));
        assertFalse(stop.contains("dispose("));
        String sent = methodSlice(serial, "private void onSent(",
                "private void onReceived(");
        assertFalse(sent.contains("开锁成功"));
        assertFalse(sent.contains("ADMIN_SERIAL_SUCCESS"));
    }

    @Test
    public void serialActivityWiresAmbiguousA1FenceAndOpenRejectionAtTheRealBoundaries()
            throws Exception {
        String serial = readMain("AdminSerialActivity.java");

        String clear = methodSlice(serial, "private void clearPendingRequest()",
                "private void finishPendingRequest(");
        assertOrdered(clear, "request.kind() == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST",
                "A1_RETRY_FENCE.onAmbiguousTerminal()");
        assertOrdered(clear, "A1_RETRY_FENCE.onAmbiguousTerminal()", "requestGate.clear()");

        String timeout = methodSlice(serial, "private void armRequestTimeout(",
                "private View buildScreen()");
        assertOrdered(timeout, "Completion.TIMEOUT", "A1_RETRY_FENCE.onAmbiguousTerminal()");
        assertOrdered(timeout, "A1_RETRY_FENCE.onAmbiguousTerminal()",
                "finishPendingRequest(request)");

        String failed = methodSlice(serial, "private void onSendFailed(",
                "private boolean isCurrentAdminCallback(");
        assertOrdered(failed, "Completion.TRANSIENT_FAILURE",
                "A1_RETRY_FENCE.onAmbiguousTerminal()");
        assertOrdered(failed, "A1_RETRY_FENCE.onAmbiguousTerminal()",
                "finishPendingRequest(request)");

        String phase = methodSlice(serial, "private void renderSerialPhase(",
                "private void renderSerialRoute(");
        assertOrdered(phase, "clearPendingRequest()", "A1_RETRY_FENCE.observe(phase)");

        String send = methodSlice(serial, "private void sendBytes(",
                "private void setDefaultSelections()");
        assertOrdered(send, "!A1_RETRY_FENCE.canBeginA1()", "requestGate.begin(");
        assertOrdered(send, "!accepted || !requestGate.accept(",
                "A1_RETRY_FENCE.onAmbiguousTerminal()");
        assertFalse(send.contains("sendUnlock()"));

        String input = methodSlice(serial, "private void sendFromInput()",
                "private void sendUnlock()");
        assertContains(input, "byte[] payload = HexCodec.decode(");
        assertContains(input, "ZipAdminScreenRouter.classifySerialCommand(payload)");
        assertFalse(input.contains("Kind.RAW_HEX"));

        String toggle = methodSlice(serial, "private void toggleConnection()",
                "private SerialConfig selectedConfig()");
        assertOrdered(toggle, "boolean accepted = current.open(selected)",
                "classifyOpenDispatch(");
        assertOrdered(toggle, "OpenDispatchResult.OPEN_REJECTED",
                "showSerialFailure(\"串口打开失败\"");
    }

    @Test
    public void everyExistingAdministratorWriteIsCapabilityGuardedBeforeItsSideEffect()
            throws Exception {
        String serial = readMain("AdminSerialActivity.java");
        String face = readMain("FaceSdkAdminActivity.java");

        String toggle = methodSlice(serial, "private void toggleConnection()",
                "private SerialConfig selectedConfig()");
        assertOrdered(toggle,
                "adminCapabilityPolicy.allows(",
                "AdminCapability.MANAGE_SERIAL_CONNECTION",
                "current.closePort()",
                "current.open(selected)");

        String send = methodSlice(serial, "private void sendBytes(",
                "private void setDefaultSelections()");
        assertOrdered(send,
                "adminCapabilityPolicy.allows(",
                "AdminCapability.OPEN_LOCKER",
                "requestGate.begin(",
                "current.send(bytes)");
        assertContains(send, "isNetworkOnline()");
        assertContains(send, ", false)");

        String activation = methodSlice(face, "private void requestActivation(",
                "private void completeLicenseOperation(");
        assertOrdered(activation,
                "adminCapabilityPolicy.allows(",
                "AdminCapability.ACTIVATE_FACE_SDK",
                "beginUiOperation(",
                "subsystem.activateOnline(");
        assertContains(activation, "isNetworkOnline()");
        assertContains(activation, ", false)");
        assertContains(activation, "Arrays.fill(ownedActivationCode, '\\0')");
    }

    @Test
    public void administratorControllerUsesAssembliesAndNeverFakesServerAuthorization()
            throws Exception {
        String main = readMain("MainActivity.java");
        assertContains(main, "adminCapabilityPolicy = AdminCapabilityAssembly.create()");
        assertContains(main, "new AdminPinOverlay(this, adminCredentialPolicy)");

        String capability = methodSlice(main, "private boolean allowsAdminCapability(",
                "private void showAdminCapabilityDenied(");
        assertFalse(capability.contains("runtime.localDemo()"));
        assertContains(capability, "isNetworkOnline()");
        assertContains(capability, ", false)");
    }

    @Test
    public void serialAndFaceBodiesHaveOneOpaqueNativeAdminPanel() throws Exception {
        String serial = readMain("AdminSerialActivity.java");
        String face = readMain("ui/FaceSdkAdminOverlay.java");
        assertContains(serial, "ADMIN_PANEL_LEFT = 20");
        assertContains(serial, "ADMIN_PANEL_TOP = 105");
        assertContains(serial, "ADMIN_PANEL_RIGHT = 1260");
        assertContains(serial, "ADMIN_PANEL_BOTTOM = 710");
        assertContains(serial, "setBackgroundColor(Color.WHITE)");
        assertContains(face, "ADMIN_PANEL_LEFT = 20");
        assertContains(face, "ADMIN_PANEL_TOP = 105");
        assertContains(face, "ADMIN_PANEL_RIGHT = 1260");
        assertContains(face, "ADMIN_PANEL_BOTTOM = 710");
        assertContains(face, "setBackgroundColor(Color.WHITE)");
        assertContains(serial, "visibleBackButton");
        assertContains(face, "visibleBackButton");
        assertContains(face, "retryButton");
        assertContains(serial, "pixelShell.setOnReturnClickListener(null)");
        assertContains(face, "shell.setOnReturnClickListener(null)");
        assertContains(serial, "unit(1050)");
        assertContains(serial, "unit(110)");
        assertContains(face, "ZipKioskShell.unit(context, 1050)");
        assertContains(face, "ZipKioskShell.unit(context, 110)");
        assertContains(face, "placeOnDesignOverlay(activationInput, context, 481, 332, 320, 48)");
        assertContains(face, "placeOnDesignOverlay(activationButton, context, 551, 398, 180, 42)");
        assertContains(face, "placeOnDesignOverlay(livenessStateView, context, 402, 342, 370, 60)");
        assertContains(face, "placeOnDesignOverlay(livenessSwitch, context, 797, 347, 72, 42)");
        assertContains(face, "placeOnDesignOverlay(retryButton, context, 470, 480, 150, 46)");
        assertContains(face, "placeOnDesignOverlay(errorBackButton, context, 660, 480, 150, 46)");
        assertContains(face, "actions.promptBackVisible()");
        assertFalse(face.contains("retryParams.leftMargin = ZipKioskShell.unit(context, 860)"));
    }

    @Test
    public void faceAdminRoutesTypedFailuresAndKeepsActivationSecretEphemeral()
            throws Exception {
        String activity = readMain("FaceSdkAdminActivity.java");
        String overlay = readMain("ui/FaceSdkAdminOverlay.java");
        assertContains(activity, "ZipAdminScreenRouter.assetForFace(");
        assertContains(activity, "BaiduFaceLicenseManager.FailureKind kind");
        assertContains(activity, "LicenseFailureKind.INVALID");
        assertContains(activity, "LicenseFailureKind.RETRYABLE");
        assertContains(activity, "FaceLicenseStateMachine.OPERATION_TIMEOUT_MILLIS");
        assertContains(activity, "WindowManager.LayoutParams.FLAG_SECURE");
        assertContains(activity, "subsystem.activateOnline(ownedActivationCode");
        assertContains(activity, "Arrays.fill(ownedActivationCode, '\\0')");
        assertFalse(activity.contains("safeMessage.contains("));
        assertFalse(activity.contains("safeCode =="));
        assertFalse(activity.contains("SharedPreferences"));
        assertFalse(activity.contains("android.util.Log"));
        String request = methodSlice(activity, "private void requestActivation(",
                "private void completeLicenseOperation(");
        assertOrdered(request, "subsystem.activateOnline(ownedActivationCode",
                "transferred = true");
        assertContains(activity, "FaceSubsystem.shared(getApplicationContext())");
        assertContains(activity, "retryUnavailableSubsystem(");

        assertContains(overlay, "PasswordTransformationMethod");
        assertContains(overlay, "setSaveEnabled(false)");
        assertContains(overlay, "setSaveFromParentEnabled(false)");
        assertContains(overlay, "editable.getChars(");
        assertContains(overlay, "editable.clear()");
        assertContains(overlay, "Arrays.fill(owned, '\\0')");
        int callback = overlay.indexOf("current.onActivateRequested(owned)");
        int transfer = overlay.indexOf("transferred = true", callback);
        assertTrue("secret ownership transfers only after the callback accepts it",
                callback >= 0 && transfer > callback);
        assertFalse(overlay.contains("getText().toString()"));
        assertFalse(overlay.contains("SharedPreferences"));
        assertFalse(overlay.contains("android.util.Log"));
    }

    @Test
    public void faceReadyNeedsCoreRuntimeButOptionalLivenessOnlyControlsSwitch()
            throws Exception {
        String activity = readMain("FaceSdkAdminActivity.java");
        String overlay = readMain("ui/FaceSdkAdminOverlay.java");
        assertContains(activity, "FaceRuntimeStateMachine.State.UNINITIALIZED");
        assertContains(activity, "startRuntimeInitialization(");
        assertContains(overlay, "snapshot.switchEnabled()");
        assertContains(overlay, "普通人脸抓拍仍可使用");
        assertContains(overlay, "ZipScreenAsset.FACE_SDK_CHECKING_LICENSE");
        assertContains(overlay, "setScreenAsset(");
    }

    @Test
    public void unitMappingIsPublicAndSerialDoesNotCopyTheFormula() throws Exception {
        String shell = readMain("ui/ZipKioskShell.java");
        String serial = readMain("AdminSerialActivity.java");
        assertContains(shell, "public static int unit(Context context, float designUnits)");
        assertContains(shell, "return ZipDesignMetrics.px(");
        assertFalse(serial.contains("getDisplayMetrics().widthPixels"));
        assertFalse(serial.contains("getDisplayMetrics().heightPixels"));
        assertFalse(serial.contains("ZipDesignMetrics.px("));
    }

    private static String readMain(String path) throws IOException {
        Path main = projectRoot().resolve("app/src/main/java/com/codex/lockertest");
        return new String(Files.readAllBytes(main.resolve(path).normalize()),
                StandardCharsets.UTF_8);
    }

    private static String methodSlice(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + Math.max(0, startToken.length()));
        assertTrue("missing method slice " + startToken, start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing source token: " + token, source.contains(token));
    }

    private static void assertOrdered(String source, String... tokens) {
        int position = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, position + 1);
            assertTrue("missing/out-of-order source token: " + token, found >= 0);
            position = found;
        }
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
