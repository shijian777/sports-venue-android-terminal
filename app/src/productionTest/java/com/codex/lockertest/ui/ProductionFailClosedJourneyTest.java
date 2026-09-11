package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.runtime.InitialLayoutPolicy;
import com.codex.lockertest.runtime.RuntimeAssembly;
import com.codex.lockertest.runtime.RuntimeServices;
import com.codex.lockertest.ui.zip.ZipBrand;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionFailClosedJourneyTest {
    private static final String SERVER_UNAVAILABLE =
            "服务器接入尚未完成，客户功能已停用";

    @Test
    public void productionReadinessRejectsEveryHomeCustomerPathBeforePolicyMutation() {
        RuntimeServices production = RuntimeAssembly.create(null);
        CountingCredentialPolicy credentials = new CountingCredentialPolicy();
        CountingLayoutPolicy layouts = new CountingLayoutPolicy();
        TerminalReadiness readiness = TerminalReadiness.serverNotConfigured();
        KioskFlowModel flow = new KioskFlowModel(credentials, layouts, readiness);
        int initialLayoutCalls = layouts.calls;

        assertFalse(production.localDemo());
        assertFalse(flow.submitCredential(UnlockMethod.PHONE, "13800138000"));
        assertFalse(flow.submitCredential(UnlockMethod.PASSWORD, "123456"));
        assertFalse(flow.submitScannedCredential("opaque-scanner-or-qr"));
        assertFalse(flow.beginFaceRecognition());
        assertFalse(flow.openUnavailable(UnlockMethod.PALM));
        assertFalse(flow.requestReturnJourney());
        assertFalse(flow.requestEnrollment());

        assertEquals(0, credentials.calls);
        assertEquals(initialLayoutCalls, layouts.calls);
        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertEquals(KioskFlowModel.ResultContext.NONE, flow.resultContext());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.pendingFaceVerification());
        assertNull(flow.pendingTarget());
        assertEquals("", flow.credentials().rawValue(UnlockMethod.PHONE));
        assertEquals("", flow.credentials().rawValue(UnlockMethod.PASSWORD));

        assertTrue(flow.openAdminPin());
        assertEquals(KioskFlowModel.Screen.ADMIN_PIN, flow.screen());
    }

    @Test
    public void productionBoundaryInvokesNoRealInjectedCustomerEffectDelegate() {
        CountingCredentialPolicy credentials = new CountingCredentialPolicy();
        CountingLayoutPolicy layouts = new CountingLayoutPolicy();
        KioskFlowModel flow = new KioskFlowModel(credentials, layouts,
                TerminalReadiness.serverNotConfigured());
        CustomerActionBoundary boundary = new CustomerActionBoundary(
                TerminalReadiness.serverNotConfigured());
        CountingCustomerEffects effects = new CountingCustomerEffects();

        assertFalse(boundary.call(CustomerActionBoundary.Effect.PHONE_CREDENTIAL,
                () -> effects.booleanEffect(
                        CustomerActionBoundary.Effect.PHONE_CREDENTIAL,
                        () -> flow.submitCredential(
                        UnlockMethod.PHONE, "13800138000")), false));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                () -> effects.booleanEffect(
                        CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                        () -> flow.submitScannedCredential(
                        "opaque-scanner-or-qr")), false));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.FACE,
                () -> effects.booleanEffect(CustomerActionBoundary.Effect.FACE,
                        flow::beginFaceRecognition), false));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.PALM,
                () -> effects.booleanEffect(CustomerActionBoundary.Effect.PALM,
                        () -> flow.openUnavailable(
                        UnlockMethod.PALM)), false));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.HOME_ENROLLMENT,
                () -> effects.booleanEffect(
                        CustomerActionBoundary.Effect.HOME_ENROLLMENT,
                        flow::requestEnrollment), false));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.RETURN_JOURNEY,
                () -> effects.booleanEffect(
                        CustomerActionBoundary.Effect.RETURN_JOURNEY,
                        flow::requestReturnJourney), false));

        assertFalse(boundary.run(CustomerActionBoundary.Effect.RETRY,
                () -> effects.voidEffect(CustomerActionBoundary.Effect.RETRY)));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.RESUME,
                () -> effects.voidEffect(CustomerActionBoundary.Effect.RESUME)));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> effects.booleanValueEffect(
                        CustomerActionBoundary.Effect.ASYNC_CALLBACK), false));
        assertEquals(0L, (long) boundary.call(
                CustomerActionBoundary.Effect.DISCOVERY,
                () -> effects.longValueEffect(
                        CustomerActionBoundary.Effect.DISCOVERY), 0L));
        assertNull(boundary.call(CustomerActionBoundary.Effect.AUTHORIZATION,
                () -> effects.objectValueEffect(
                        CustomerActionBoundary.Effect.AUTHORIZATION), null));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.RETURN_SERVICE,
                () -> effects.voidEffect(
                        CustomerActionBoundary.Effect.RETURN_SERVICE)));
        assertNull(boundary.call(CustomerActionBoundary.Effect.SERIAL_CONNECT,
                () -> effects.objectValueEffect(
                        CustomerActionBoundary.Effect.SERIAL_CONNECT), null));
        assertFalse(boundary.call(CustomerActionBoundary.Effect.SERIAL_SEND,
                () -> effects.booleanValueEffect(
                        CustomerActionBoundary.Effect.SERIAL_SEND), false));

        effects.assertNoCalls();
        assertEquals(0, credentials.calls);
        assertTrue(flow.openAdminPin());
        assertEquals(KioskFlowModel.Screen.ADMIN_PIN, flow.screen());
    }

    @Test
    public void localDemoReadinessKeepsExistingCustomerEntrySemantics() {
        CredentialAdmissionPolicy accept = (method, value) ->
                CredentialAdmission.accepted(method);
        InitialLayoutPolicy empty = new CountingLayoutPolicy();

        KioskFlowModel phone = new KioskFlowModel(
                accept, empty, TerminalReadiness.localDemoReady());
        assertTrue(phone.submitCredential(UnlockMethod.PHONE, "13800138000"));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, phone.screen());

        KioskFlowModel scanner = new KioskFlowModel(
                accept, empty, TerminalReadiness.localDemoReady());
        assertTrue(scanner.submitScannedCredential("opaque-scanner-or-qr"));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, scanner.screen());

        KioskFlowModel other = new KioskFlowModel(
                accept, empty, TerminalReadiness.localDemoReady());
        assertTrue(other.beginFaceRecognition());
        other.returnHome();
        assertTrue(other.openUnavailable(UnlockMethod.PALM));
        other.returnHome();
        assertTrue(other.requestReturnJourney());
        assertTrue(other.requestEnrollment());
        assertTrue(other.openAdminPin());
    }

    @Test
    public void activityInstallsVariantReadinessAndGatesInputsBeforeSideEffects()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String onCreate = slice(main,
                "    protected void onCreate(Bundle savedInstanceState)",
                "    @Override\n    protected void onStart()");
        assertContains(onCreate,
                "bootstrapRuntime = BootstrapAssembly.create(getApplicationContext())");
        assertContains(onCreate, "bootstrapRuntime.readinessSource()");
        assertContains(onCreate,
                "customerActionBoundary = new CustomerActionBoundary(terminalReadinessSource)");
        assertContains(onCreate,
                "new KioskFlowModel(credentialPolicy, runtime.layoutPolicy(), terminalReadinessSource)");
        assertFalse(onCreate.contains("BuildConfig"));
        assertFalse(onCreate.contains("getClass()"));

        String dispatch = slice(main,
                "    public boolean dispatchKeyEvent(KeyEvent event)",
                "    private void returnHome(String reason)");
        assertBefore(dispatch, "!customerActionsEnabled()", "idCardEventDrain.shouldConsume");
        assertBefore(dispatch, "!customerActionsEnabled()", "acceptPassiveCredentialCharacter");

        assertGateBefore(main, "    private void submitCredential(",
                "flow.submitCredential(method, rawValue)");
        assertGateBefore(main, "    private void beginFaceRecognition()",
                "invalidateCustomerWork(true)");
        assertGateBefore(main, "    private void openUnavailable(",
                "flow.openUnavailable(method)");
        assertGateBefore(main, "    private void beginReturnJourney()",
                "returnFlowController.begin()");
        assertGateBefore(main, "    private void submitReturnIdentity(",
                "RETURN_SERVICE_EXECUTOR.execute(");
        assertGateBefore(main, "    private void confirmReturnLocker(",
                "returnFlowController.snapshot()");
        assertGateBefore(main, "    private void startReturnSerialOperation(",
                "reserveCustomerOperation(CustomerOperationKind.RETURN)");
        assertGateBefore(main, "    private void startReturnStatusResume()",
                "reserveCustomerOperation(CustomerOperationKind.RETURN)");
        assertGateBefore(main, "    private void sendReturnDoorStatusQuery()",
                "customerSerialTransmitter.send(");
        assertGateBefore(main, "    private void startReturnAuthorizedUnlock()",
                "returnUnlockCoordinator.startAuthorized(");
        assertGateBefore(main, "    private void startDiscoveryOperation()",
                "discoveryCoordinator.start()");
        assertGateBefore(main, "    private void submitLocker(",
                "customerUnlockAuthorizer.authorize(");
        assertSerialGateBefore(main, "    private boolean writeAuthorizedCustomerPayload(",
                "SERIAL_GATEWAY_OWNER.withGateway(");
        assertGateBefore(main, "    private boolean sendAuthorizedReturnUnlock(",
                "customerSerialTransmitter.send(");

        assertEquals(2, occurrences(main, "SERIAL_GATEWAY_OWNER.acquire("));
        assertEquals(5, occurrences(main, "customerSerialTransmitter.beginOperation("));
        assertEquals(3, occurrences(main, "customerSerialTransmitter.send("));
        assertEquals(1, occurrences(main, "gateway.send("));
        assertSerialGateBefore(main, "    private void bindCustomerGateway(",
                "SERIAL_GATEWAY_OWNER.acquire(");

        String currentOperation = slice(main,
                "    private boolean isCurrentOperation(",
                "    private boolean isCurrentGatewayCallback(");
        assertBefore(currentOperation, "serialOperationEnabled()", "&& active");

        String enrollment = slice(main,
                "    private void beginEnrollment(EnrollmentOrigin origin)",
                "    private void renderEnrollmentChoice()");
        assertContains(enrollment, "origin == EnrollmentOrigin.HOME");
        assertBefore(enrollment, "!customerActionsEnabled()", "cancelFaceWorkAndInvalidate()");

        String admin = slice(main,
                "    private void showAdminPin()",
                "    private void renderAdminPin()");
        assertFalse(admin.contains("customerActionsEnabled()"));

        String onStart = slice(main,
                "    protected void onStart()",
                "    @Override\n    protected void onStop()");
        assertContains(onStart, "ensureInitialDefaultConnection()");
        assertBefore(onStart, "customerActionsEnabled() && returnJourneyActive",
                "resumeReturnJourneyFromBackground()");

        assertGateBefore(main, "    private void ensureInitialDefaultConnection()",
                "SERIAL_GATEWAY_OWNER.acquire(");
    }

    @Test
    public void activityRoutesRealCustomerDelegatesThroughTheFiniteBoundary()
            throws Exception {
        String main = serialAwareDelegates(read("app/src/main/java/com/codex/lockertest/MainActivity.java"));

        assertBoundaryEffectOccurrences(main, "PHONE_CREDENTIAL", 2);
        assertBoundaryEffectOccurrences(main, "SCANNER_CREDENTIAL", 3);
        assertBoundaryEffectOccurrences(main, "FACE", 1);
        assertBoundaryEffectOccurrences(main, "PALM", 2);
        assertBoundaryEffectOccurrences(main, "HOME_ENROLLMENT", 1);
        assertBoundaryEffectOccurrences(main, "RETURN_JOURNEY", 1);
        assertBoundaryEffectOccurrences(main, "RETRY", 10);
        assertBoundaryEffectOccurrences(main, "RESUME", 2);
        assertBoundaryEffectOccurrences(main, "ASYNC_CALLBACK", 12);
        assertBoundaryEffectOccurrences(main, "DISCOVERY", 1);
        assertBoundaryEffectOccurrences(main, "AUTHORIZATION", 1);
        assertBoundaryEffectOccurrences(main, "RETURN_SERVICE", 7);
        assertBoundaryEffectOccurrences(main, "SERIAL_CONNECT", 2);
        assertBoundaryEffectOccurrences(main, "SERIAL_SEND", 4);
        assertEquals(49, occurrences(main, "CustomerActionBoundary.Effect."));
        assertAllBoundaryDelegatesAreLazy(main, 49);

        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.DISCOVERY",
                "discoveryCoordinator.start()", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.AUTHORIZATION",
                "customerUnlockAuthorizer.authorize(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.authenticate(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryQuery(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.selectAndAuthorize(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryAuthorization(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.restartAfterUnlockFailure(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryCommit(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.markStableDoorClosed(", 1);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.SERIAL_CONNECT",
                "SERIAL_GATEWAY_OWNER.acquire(", 2);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "customerSerialTransmitter.send(", 3);
        assertWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "gateway.send(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.authenticate(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryQuery(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.selectAndAuthorize(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryAuthorization(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryCommit(", 1);
        assertWorkerWrappedOccurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.markStableDoorClosed(", 1);
        assertEquals(7, occurrences(main, "RETURN_SERVICE_EXECUTOR.execute("));

        assertEffectBefore(main, "    private void submitCredential(",
                "    private void openUnavailable(",
                "CustomerActionBoundary.Effect.PHONE_CREDENTIAL",
                "flow.submitCredential(method, rawValue)");
        assertEffectBefore(main, "    private void finishPassiveCredentialInput(",
                "    private void submitReturnScannedCredential(",
                "CustomerActionBoundary.Effect.SCANNER_CREDENTIAL",
                "flow.submitScannedCredential(rawCredential)");
        assertEffectBefore(main, "    private void beginFaceRecognition()",
                "    private void renderFaceRecognition(",
                "CustomerActionBoundary.Effect.FACE",
                "flow::beginFaceRecognition");
        assertEffectBefore(main, "    private void openUnavailable(",
                "    private void renderUnavailable()",
                "CustomerActionBoundary.Effect.PALM",
                "flow.openUnavailable(method)");
        assertEffectBefore(main, "    private void beginEnrollment(EnrollmentOrigin origin)",
                "    private void renderEnrollmentChoice()",
                "CustomerActionBoundary.Effect.HOME_ENROLLMENT",
                "flow::requestEnrollment");
        assertEffectBefore(main, "    private void beginReturnJourney()",
                "    private void renderReturnSnapshot()",
                "CustomerActionBoundary.Effect.RETURN_JOURNEY",
                "flow::requestReturnJourney");

        assertEffectBefore(main, "    private void retryReturnQuery()",
                "    private void beginReturnPalmRecognition()",
                "CustomerActionBoundary.Effect.RETRY",
                "returnAsyncEpoch = epoch");
        assertEffectBefore(main, "    protected void onStart()",
                "    @Override\n    protected void onStop()",
                "CustomerActionBoundary.Effect.RESUME",
                "resumeReturnJourneyFromBackground()");
        assertEffectBefore(main, "    private boolean isCurrentReturnAsync(",
                "    private boolean isNetworkOnline()",
                "CustomerActionBoundary.Effect.ASYNC_CALLBACK",
                "returnFlowController.snapshot()");
        assertEffectBefore(main, "    private void startDiscoveryOperation()",
                "    private void submitLocker(",
                "CustomerActionBoundary.Effect.DISCOVERY",
                "discoveryCoordinator.start()");
        assertEffectBefore(main, "    private void submitLocker(",
                "    private static boolean samePhysicalTarget(",
                "CustomerActionBoundary.Effect.AUTHORIZATION",
                "customerUnlockAuthorizer.authorize(");

        assertEffectBefore(main, "    private void submitReturnIdentity(",
                "    private void retryReturnQuery()",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.authenticate(");
        assertEffectBefore(main, "    private void retryReturnQuery()",
                "    private void beginReturnPalmRecognition()",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryQuery(");
        assertEffectBefore(main, "    private void confirmReturnLocker(",
                "    private void retryReturnAuthorization(",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.selectAndAuthorize(");
        assertEffectBefore(main, "    private void retryReturnAuthorization(",
                "    private void failReturnAuthorizationBeforeUnlock(",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryAuthorization(");
        assertEffectBefore(main, "    private void retryPresentedReturnAction()",
                "    private void restartReturnIdentityEntry()",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.restartAfterUnlockFailure(");
        assertEffectBefore(main, "    private void retryReturnCommit(",
                "    private void cancelReturnJourneyFromUser()",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.retryCommit(");
        assertEffectBefore(main, "    private void completeReturnAfterVerifiedClose()",
                "    private void clearCompletedReturnPhysicalOperation()",
                "CustomerActionBoundary.Effect.RETURN_SERVICE",
                "returnFlowController.markStableDoorClosed(");
        assertEquals(7, occurrences(main,
                "CustomerActionBoundary.Effect.RETURN_SERVICE"));
        assertEquals(7, occurrences(main, "RETURN_SERVICE_EXECUTOR.execute("));
        assertEquals(2, occurrences(main,
                "CustomerActionBoundary.Effect.SERIAL_CONNECT"));
        assertEquals(2, occurrences(main, "SERIAL_GATEWAY_OWNER.acquire("));
        assertEquals(4, occurrences(main,
                "CustomerActionBoundary.Effect.SERIAL_SEND"));
        assertEquals(3, occurrences(main, "customerSerialTransmitter.send("));
        assertEquals(1, occurrences(main, "gateway.send("));
        assertEffectBefore(main, "    private void ensureInitialDefaultConnection()",
                "    private void renderScreen()",
                "CustomerActionBoundary.Effect.SERIAL_CONNECT",
                "SERIAL_GATEWAY_OWNER.acquire(");
        assertEffectBefore(main, "    private void bindCustomerGateway(",
                "    private void applyCustomerConnectionAction(",
                "CustomerActionBoundary.Effect.SERIAL_CONNECT",
                "SERIAL_GATEWAY_OWNER.acquire(");
        assertEffectBefore(main, "    private void sendReturnDoorStatusQuery()",
                "    private void startReturnAuthorizedUnlock()",
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "customerSerialTransmitter.send(");
        assertEffectBefore(main, "    private boolean sendForOperation(",
                "    private boolean writeAuthorizedCustomerPayload(",
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "customerSerialTransmitter.send(");
        assertEffectBefore(main, "    private boolean writeAuthorizedCustomerPayload(",
                "    private static SerialWriteAttribution discoveryAttribution(",
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "gateway.send(");
        assertEffectBefore(main, "    private boolean sendAuthorizedReturnUnlock(",
                "    private static String returnUnlockDispatchFailureMessage(",
                "CustomerActionBoundary.Effect.SERIAL_SEND",
                "customerSerialTransmitter.send(");

        String admin = slice(main,
                "    private void showAdminPin()",
                "    private void renderAdminPin()");
        assertFalse(admin.contains("CustomerActionBoundary"));
    }

    @Test
    public void balancedBoundaryContractRejectsAdjacentButUnwrappedActualCall() {
        String falsePositive =
                "customerActionBoundary.run(\n"
                + "        CustomerActionBoundary.Effect.DISCOVERY,\n"
                + "        () -> { });\n"
                + "discoveryCoordinator.start();";
        assertBoundaryFixtureRejected(falsePositive,
                "an adjacent boundary token must not prove an unwrapped call");
    }

    @Test
    public void balancedBoundaryContractRejectsDeadTextAndIdentifierSuffixes() {
        String invocation = "customerActionBoundary.run("
                + "CustomerActionBoundary.Effect.DISCOVERY, "
                + "() -> discoveryCoordinator.start());";
        assertBoundaryFixtureRejected("// " + invocation,
                "a line-comment invocation is not executable wiring");
        assertBoundaryFixtureRejected("/* " + invocation + " */",
                "a block-comment invocation is not executable wiring");
        assertBoundaryFixtureRejected("String dead = \"" + invocation + "\";",
                "a string-literal invocation is not executable wiring");
        assertBoundaryFixtureRejected("not" + invocation,
                "a customerActionBoundary identifier suffix is not the boundary");
    }

    @Test
    public void balancedBoundaryContractRejectsEagerDelegateShapes() {
        assertBoundaryFixtureRejected(
                "customerActionBoundary.run("
                        + "CustomerActionBoundary.Effect.DISCOVERY, "
                        + "new CustomerActionBoundary.Action() {"
                        + " { discoveryCoordinator.start(); }"
                        + " public void run() { } });",
                "an anonymous-class initializer is eager, not a lazy delegate");
        assertBoundaryFixtureRejected(
                "customerActionBoundary.run("
                        + "CustomerActionBoundary.Effect.DISCOVERY, "
                        + "factory(discoveryCoordinator.start()));",
                "an arbitrary factory expression is not a lazy delegate");
        assertBoundaryFixtureRejected(
                "customerActionBoundary.run("
                        + "CustomerActionBoundary.Effect.DISCOVERY, "
                        + "factory(discoveryCoordinator.start())::run);",
                "a method-reference receiver must not execute the marker");
    }

    @Test
    public void balancedBoundaryContractRejectsLongerReceiverMarkerPrefix() {
        assertBoundaryFixtureRejected(
                "customerActionBoundary.run("
                        + "CustomerActionBoundary.Effect.DISCOVERY, "
                        + "() -> notdiscoveryCoordinator.start());",
                "CustomerActionBoundary.Effect.DISCOVERY",
                "discoveryCoordinator.start()",
                "a longer receiver identifier must not impersonate the marker");
    }

    @Test
    public void balancedBoundaryContractRejectsLongerMethodReferenceTarget() {
        assertBoundaryFixtureRejected(
                "customerActionBoundary.call("
                        + "CustomerActionBoundary.Effect.FACE, "
                        + "flow::beginFaceRecognitionAllowed, false);",
                "CustomerActionBoundary.Effect.FACE",
                "flow::beginFaceRecognition",
                "a longer method-reference target must not impersonate the marker");
    }

    @Test
    public void balancedBoundaryContractRejectsQualifiedLambdaMarker() {
        assertBoundaryFixtureRejected(
                "customerActionBoundary.run("
                        + "CustomerActionBoundary.Effect.DISCOVERY, "
                        + "() -> holder . discoveryCoordinator.start());",
                "a qualified field must not impersonate the required receiver");
    }

    @Test
    public void balancedBoundaryContractRejectsQualifiedMethodReferenceMarker() {
        assertBoundaryFixtureRejected(
                "customerActionBoundary.call("
                        + "CustomerActionBoundary.Effect.FACE, "
                        + "holder./* owner */flow::beginFaceRecognition, false);",
                "CustomerActionBoundary.Effect.FACE",
                "flow::beginFaceRecognition",
                "a qualified field must not impersonate the method receiver");
    }

    @Test
    public void permissionAndReturnAuthCallbacksRejectBeforeUnavailableSideEffects()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String permissions = slice(main,
                "    public void onRequestPermissionsResult(",
                "    private void postFaceCallback(");
        String face = slice(permissions,
                "        if (requestCode == FACE_CAMERA_PERMISSION_REQUEST) {",
                "        if (requestCode == ENROLLMENT_CAMERA_PERMISSION_REQUEST) {");
        assertFirstStatementStartsWith(face, "if (!faceActionsEnabled())");
        assertIsolatedFaceReadinessReturn(face);
        assertBefore(face, "!faceActionsEnabled()",
                "clearFacePermissionRequest()");
        assertBefore(face, "!faceActionsEnabled()", "startFaceController(");
        assertContains(face, "&& !isOnlineFaceActive()" );

        String onlineFace = slice(main,
                "    private boolean isOnlineFaceActive()",
                "    private boolean faceActionsEnabled()");
        assertContains(onlineFace, "active && !isFinishing()");
        assertContains(onlineFace, "onlineCustomerHost.isCurrentFace(onlineFaceClient)");
        String faceActions = slice(main,
                "    private boolean faceActionsEnabled()",
                "    private void renderFaceRecognition()");
        assertContains(faceActions,
                "return customerActionsEnabled() || isOnlineFaceActive();");

        String enrollment = slice(permissions,
                "        if (requestCode == ENROLLMENT_CAMERA_PERMISSION_REQUEST) {",
                "        super.onRequestPermissionsResult(");
        assertFirstStatementStartsWith(enrollment,
                "EnrollmentOrigin permissionOrigin = enrollmentOrigin;");
        assertContains(enrollment,
                "permissionOrigin != EnrollmentOrigin.ADMIN");
        assertBefore(enrollment, "permissionOrigin != EnrollmentOrigin.ADMIN",
                "!customerActionsEnabled()");
        assertBefore(enrollment, "!customerActionsEnabled()",
                "clearEnrollmentPermissionRequest()");
        assertBefore(enrollment, "!customerActionsEnabled()", "safeFaceSnapshot()");
        assertIsolatedReadinessReturn(enrollment,
                "if (permissionOrigin != EnrollmentOrigin.ADMIN",
                "return;");

        String returnAuth = slice(main,
                "    private void renderReturnAuthentication(",
                "    private void renderReturnLockerSelection(");
        String faceListener = slice(returnAuth,
                "                public void onFaceRequested() {",
                "                public void onPalmRequested() {");
        assertFirstIsolatedReadinessGate(faceListener);
        assertBefore(faceListener, "!customerActionsEnabled()",
                "isReturnQueryRetryable()");
        String palmListener = slice(returnAuth,
                "                public void onPalmRequested() {",
                "                public void onCancelRequested() {");
        assertFirstIsolatedReadinessGate(palmListener);
        assertBefore(palmListener, "!customerActionsEnabled()",
                "isReturnQueryRetryable()");

        String queryRetryable = slice(main,
                "    private boolean isReturnQueryRetryable()",
                "    private boolean isReturnQueryRetryable(ReturnFlowController.Snapshot snapshot)");
        assertFirstIsolatedBooleanReadinessGate(queryRetryable);
        assertBefore(queryRetryable, "!customerActionsEnabled()", "return false;");
        assertBefore(queryRetryable, "!customerActionsEnabled()",
                "returnFlowController.snapshot()");
    }

    @Test
    public void onlyExplicitAdminEnrollmentOriginBypassesCustomerReadiness()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String retry = slice(main,
                "    private void retryFaceEnrollmentPreflight()",
                "    private void postEnrollmentCallback(");
        assertExplicitAdminOnlyEnrollmentRouting(retry,
                "beginFaceEnrollmentPreflight();",
                "CustomerActionBoundary.Effect.RETRY");

        String currentView = slice(main,
                "    private boolean isCurrentEnrollmentView(",
                "    private boolean isCurrentEnrollmentChoice(");
        assertExplicitAdminOnlyEnrollmentRouting(currentView,
                "return active && enrollmentJourneyActive",
                "CustomerActionBoundary.Effect.ASYNC_CALLBACK");
        String currentChoice = slice(main,
                "    private boolean isCurrentEnrollmentChoice(",
                "    private void showEnrollmentBlocker(");
        assertExplicitAdminOnlyEnrollmentRouting(currentChoice,
                "return active && enrollmentJourneyActive",
                "CustomerActionBoundary.Effect.ASYNC_CALLBACK");

        assertFalse(main.contains("enrollmentOrigin != EnrollmentOrigin.HOME"));
        String callback = slice(main,
                "    private boolean isCurrentEnrollmentCallback(",
                "    private boolean isCurrentEnrollmentView(");
        assertContains(callback, "isCurrentEnrollmentView(");
        String barrier = slice(main,
                "    private boolean isCurrentEnrollmentBarrier(",
                "    private void failEnrollmentCleanupBarrier(");
        assertContains(barrier, "isCurrentEnrollmentView(");

        String nullBypassFixture =
                "if (enrollmentOrigin != EnrollmentOrigin.HOME) {\n"
                + "    beginFaceEnrollmentPreflight();\n"
                + "    return;\n"
                + "}\n"
                + "customerActionBoundary.run(\n"
                + "    CustomerActionBoundary.Effect.RETRY,\n"
                + "    this::beginFaceEnrollmentPreflight);";
        assertEnrollmentRoutingRejected(nullBypassFixture,
                "null is not HOME and must never enter a direct branch");
    }

    @Test
    public void enrollmentRoutingContractRejectsCommentedAdminEvidence() {
        String fixture =
                "// if (enrollmentOrigin == EnrollmentOrigin.ADMIN)\n"
                + "if (enrollmentOrigin == null) {\n"
                + "    beginFaceEnrollmentPreflight();\n"
                + "    return;\n"
                + "}\n"
                + "customerActionBoundary.run(\n"
                + "    CustomerActionBoundary.Effect.RETRY,\n"
                + "    this::beginFaceEnrollmentPreflight);";
        assertEnrollmentRoutingRejected(fixture,
                "a commented ADMIN condition must not prove an exemption");
    }

    @Test
    public void enrollmentRoutingContractRejectsCommentedDirectEvidence() {
        String fixture =
                "if (enrollmentOrigin == EnrollmentOrigin.ADMIN) {\n"
                + "    // beginFaceEnrollmentPreflight();\n"
                + "}\n"
                + "if (enrollmentOrigin == null) {\n"
                + "    beginFaceEnrollmentPreflight();\n"
                + "    return;\n"
                + "}\n"
                + "customerActionBoundary.run(\n"
                + "    CustomerActionBoundary.Effect.RETRY,\n"
                + "    this::beginFaceEnrollmentPreflight);";
        assertEnrollmentRoutingRejected(fixture,
                "a commented direct call must not hide a null-origin bypass");
    }

    @Test
    public void enrollmentRoutingContractBindsAdminConditionToItsOwnBlock() {
        String fixture =
                "if (enrollmentOrigin == EnrollmentOrigin.ADMIN) return;\n"
                + "if (enrollmentOrigin == null) {\n"
                + "    beginFaceEnrollmentPreflight();\n"
                + "    return;\n"
                + "}\n"
                + "customerActionBoundary.run(\n"
                + "    CustomerActionBoundary.Effect.RETRY,\n"
                + "    this::beginFaceEnrollmentPreflight);";
        assertEnrollmentRoutingRejected(fixture,
                "the ADMIN condition must own the direct-call block");
    }

    @Test
    public void everyRetryResumeAndAsyncEntryRejectsBeforeItsFirstSideEffect()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");

        String retryQuery = slice(main,
                "    private void retryReturnQuery()",
                "    private void beginReturnPalmRecognition()");
        assertFirstIsolatedReadinessGate(retryQuery);
        assertBefore(retryQuery, "!customerActionsEnabled()", "isNetworkOnline()");
        assertBefore(retryQuery, "!customerActionsEnabled()", "returnStatusMessage =");
        assertBefore(retryQuery, "!customerActionsEnabled()", "renderReturnSnapshot()");

        String retryPresented = slice(main,
                "    private void retryPresentedReturnAction()",
                "    private void restartReturnIdentityEntry()");
        assertFirstIsolatedReadinessGate(retryPresented);
        assertBefore(retryPresented, "!customerActionsEnabled()",
                "returnFlowController.snapshot()");

        String restartIdentity = slice(main,
                "    private void restartReturnIdentityEntry()",
                "    private void retryPresentedReturnCommit(");
        assertFirstIsolatedReadinessGate(restartIdentity);
        assertBefore(restartIdentity, "!customerActionsEnabled()",
                "invalidateCustomerWork(true)");

        String retryCommit = slice(main,
                "    private void retryReturnCommit(",
                "    private void cancelReturnJourneyFromUser()");
        assertFirstIsolatedReadinessGate(retryCommit);
        assertBefore(retryCommit, "!customerActionsEnabled()", "isNetworkOnline()");

        String retryPresentedCommit = slice(main,
                "    private void retryPresentedReturnCommit(",
                "    /** Compatibility entry retained");
        assertFirstIsolatedReadinessGate(retryPresentedCommit);
        String compatibilityRetry = slice(main,
                "    private void retryReturnJourney()",
                "    private void retryReturnCommit(");
        assertFirstIsolatedReadinessGate(compatibilityRetry);

        String resume = slice(main,
                "    private void resumeReturnJourneyFromBackground()",
                "    /**\n     * Runs after any pre-background return service call");
        assertFirstIsolatedReadinessGate(resume);
        assertBefore(resume, "!customerActionsEnabled()", "returnBackgrounded = false");

        String reconcileQueue = slice(main,
                "    private void queueReturnCommitReconcile(",
                "    private void reconcileReturnCommitState(");
        assertFirstIsolatedReadinessGate(reconcileQueue);
        assertBefore(reconcileQueue, "!customerActionsEnabled()", "returnAsyncEpoch = epoch");

        String complete = slice(main,
                "    private void completeReturnAfterVerifiedClose()",
                "    private void clearCompletedReturnPhysicalOperation()");
        assertFirstIsolatedReadinessGate(complete);
        assertBefore(complete, "!customerActionsEnabled()", "isNetworkOnline()");

        String currentAsync = slice(main,
                "    private boolean isCurrentReturnAsync(",
                "    private boolean isNetworkOnline()");
        assertBefore(currentAsync, "customerActionsEnabled()",
                "returnFlowController.snapshot()");

        String resultRetry = slice(main,
                "    private ResultOverlay createResultOverlay()",
                "    private void showAdminPin()");
        assertBefore(resultRetry, "!customerActionsEnabled()",
                "invalidateCustomerWork(true)");
        assertBefore(resultRetry, "CustomerActionBoundary.Effect.RETRY",
                "invalidateCustomerWork(true)");

        String faceRetry = slice(main,
                "    private void renderFaceRecognition()",
                "    private void awaitFaceCleanupBarrier(");
        assertBefore(faceRetry, "!faceActionsEnabled()",
                "clearFaceDeferredFailure()");
        assertBefore(faceRetry, "if (isOnlineFaceActive())",
                "clearFaceDeferredFailure()");
        String legacyFaceRetry = faceRetry.substring(
                faceRetry.indexOf("customerActionBoundary.run("));
        assertWrappedOccurrences(legacyFaceRetry,
                "CustomerActionBoundary.Effect.RETRY",
                "clearFaceDeferredFailure();", 1);

        String enrollmentRetry = slice(main,
                "    private void retryFaceEnrollmentPreflight()",
                "    private void postEnrollmentCallback(");
        assertBefore(enrollmentRetry, "EnrollmentOrigin.ADMIN",
                "CustomerActionBoundary.Effect.RETRY");
        assertBefore(enrollmentRetry, "CustomerActionBoundary.Effect.RETRY",
                "this::beginFaceEnrollmentPreflight");

        String faceAsync = slice(main,
                "    private boolean isCurrentFaceCallback(",
                "    private void renderFaceState(");
        assertBefore(faceAsync, "CustomerActionBoundary.Effect.ASYNC_CALLBACK",
                "flow.screen()");
        String enrollmentAsync = slice(main,
                "    private boolean isCurrentEnrollmentView(",
                "    private boolean isCurrentEnrollmentChoice(");
        assertBefore(enrollmentAsync, "EnrollmentOrigin.ADMIN",
                "CustomerActionBoundary.Effect.ASYNC_CALLBACK");
    }

    @Test
    public void homeRendersExactUnavailableCopyWithoutChangingBrandOrApprovedGeometry()
            throws Exception {
        String home = read("app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java");

        assertContains(home, "bootstrapPresentation.statusMessage()");
        assertContains(home, "BootstrapHomePresentation bootstrapPresentation");
        assertContains(home, "setCustomerInteractionsEnabled(false)");
        assertContains(home, "registerCustomerAction(phoneField)");
        assertContains(home, "registerCustomerAction(passwordField)");
        assertContains(home, "registerCustomerAction(keypad)");
        assertContains(home, "registerCustomerAction(confirmButton)");
        assertContains(home, "registerCustomerAction(faceButton)");
        assertContains(home, "registerCustomerAction(enrollmentButton)");
        assertContains(home, "registerCustomerAction(returnButton)");
        assertFalse(home.contains("registerCustomerAction(admin)"));
        assertFalse(home.contains("registerCustomerAction(clock)"));
        assertFalse(home.contains("registerCustomerAction(bootstrapRetryButton)"));
        assertContains(home, "modalInterceptionLayer.setClickable(true)");
        String unavailableLayer = slice(home,
                "        if (!bootstrapPresentation.customerActionsEnabled()) {",
                "        setCustomerInteractionsEnabled(true);");
        assertBefore(unavailableLayer, "setCustomerInteractionsEnabled(false)",
                "pixelShell.setPromptDimmed(true)");
        assertBefore(unavailableLayer, "pixelShell.setPromptDimmed(true)",
                "modalInterceptionLayer.setVisibility(View.VISIBLE)");
        assertBefore(unavailableLayer, "modalInterceptionLayer.setVisibility(View.VISIBLE)",
                "terminalUnavailableModal.setVisibility(View.VISIBLE)");
        assertBefore(unavailableLayer, "terminalUnavailableModal.bringToFront()",
                "clock.bringToFront()");
        assertFalse(unavailableLayer.contains(
                "modalInterceptionLayer.setVisibility(View.GONE)"));

        assertContains(home, "place(content, phoneField, 435, 101, 439, 63)");
        assertContains(home, "place(content, passwordField, 435, 197, 439, 63)");
        assertContains(home, "place(content, keypad, 436, 291, 436, 334)");
        assertContains(home, "place(content, confirmButton, 436, 657, 438, 60)");
        assertContains(home, "place(content, rightInteractions, 900, 99, 336, 621)");
        assertFalse(home.contains("place(panel, admin,"));
        assertContains(home, "place(overlay, clock, 970, 5, 275, 55)");
        String modalClockLayer = slice(home,
                "        modalInterceptionLayer.bringToFront();",
                "        if (validationErrorVisible) {");
        assertBefore(modalClockLayer, "modalInterceptionLayer.bringToFront()",
                "clock.bringToFront()");
        assertFalse(home.contains("adminEntryButton"));
        assertFalse(home.contains("\"管理员入口\""));
        assertContains(home, "clockAdminTapGate.tap(SystemClock.elapsedRealtime())");
        assertContains(home, "place(overlay, terminalUnavailableModal, 330, 225, 620, 400)");

        assertEquals(1280, ZipBrand.DESIGN_WIDTH);
        assertEquals(800, ZipBrand.DESIGN_HEIGHT);
        assertEquals("乾卦智能柜自助终端", ZipBrand.HOME_TITLE);
        assertEquals("乾卦SaaS管理系统(gmtfit.com)", ZipBrand.FOOTER);
        assertEquals("版本：v21 测试版", ZipBrand.VERSION);
    }

    private static void assertGateBefore(String source, String methodMarker,
            String sideEffectMarker) {
        int start = source.indexOf(methodMarker);
        if (start < 0) fail("missing method: " + methodMarker);
        int sideEffect = source.indexOf(sideEffectMarker, start);
        if (sideEffect < 0) fail("missing side effect: " + sideEffectMarker);
        int gate = source.indexOf("!customerActionsEnabled()", start);
        assertTrue("readiness gate must precede " + sideEffectMarker,
                gate >= start && gate < sideEffect);
    }

    private static void assertEffectBefore(String source, String marker,
            String next, String effect, String delegate) {
        String method = slice(source, marker, next);
        assertWrappedOccurrences(method, effect, delegate, 1);
    }

    @Test
    public void onlineSerialBranchRequiresSpecificAuthorityWithoutEnablingLegacyActions() throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String authority = slice(main, "    private boolean onlineSerialAuthorityCurrent()",
                "    private boolean serialOperationEnabled()");
        for (String check : new String[]{"CustomerOperationKind.ONLINE_UNLOCK", "onlineUnlockPermit.active()",
                "!onlineUnlockCancelled.get()", "onlineUnlockListener.isCurrent()", "onlineHostCurrent()"})
            assertContains(authority, check);
        String enabled = slice(main, "    private boolean serialOperationEnabled()",
                "    private <T> T callSerialEffect(");
        assertContains(enabled, "? onlineSerialAuthorityCurrent() : customerActionsEnabled()");
        String wrapper = slice(main, "    private <T> T callSerialEffect(",
                "    private final class OnlinePhysicalExecutor");
        assertContains(wrapper, "customerOperationKind != CustomerOperationKind.ONLINE_UNLOCK");
        assertContains(wrapper, "return customerActionBoundary.call(effect, action, unavailable)");
        assertContains(wrapper, "return onlineSerialAuthorityCurrent() ? action.call() : unavailable");
        assertContains(wrapper, "effect != CustomerActionBoundary.Effect.SERIAL_CONNECT");
        assertContains(wrapper, "&& effect != CustomerActionBoundary.Effect.SERIAL_SEND");
        assertContains(wrapper, "&& effect != CustomerActionBoundary.Effect.ASYNC_CALLBACK) return unavailable");
        String write = slice(main, "    private boolean writeAuthorizedCustomerPayload(",
                "    private static SerialWriteAttribution discoveryAttribution(");
        assertContains(write, "gateway.sendAuthorized(safePayload, permit,");
        assertContains(write, "onlineUnlockPermit == permit");
        assertContains(write, "!cancelled.get()");
        assertContains(write, "onlineAuthority.isCurrent()");
        assertContains(write, "SERIAL_GATEWAY_OWNER.isCurrent(expectedLease)");
        assertContains(write, "&& isNetworkOnline()");
        String executor = slice(main, "    private final class OnlinePhysicalExecutor",
                "    private void reserveCustomerOperation(");
        assertContains(executor, "permit.cancel()");
        assertBefore(executor, "listener.registerCancellation(cancellation)",
                "if (cancelled.get()) return");
        String invalidate = slice(main, "    private void invalidateCustomerWork(",
                "    private void finishActiveCustomerOperation()");
        assertBefore(invalidate, "onlineUnlockPermit.cancel()", "detachCustomerGatewayAfterInvalidation()");
        assertFalse(invalidate.contains("closePort("));
        assertFalse(invalidate.contains("dispose("));
    }

    /** The new four call sites retain the same lazy-effect contract; its strict gate is tested above. */
    private static String serialAwareDelegates(String main) {
        String wrapper = slice(main, "    private <T> T callSerialEffect(",
                "    private final class OnlinePhysicalExecutor");
        String delegates = main.replace(wrapper, "");
        assertEquals(4, occurrences(delegates, "callSerialEffect("));
        return delegates.replace("callSerialEffect(", "customerActionBoundary.call(");
    }

    private static void assertSerialGateBefore(String source, String method, String effect) {
        int start = source.indexOf(method);
        int gate = source.indexOf("!serialOperationEnabled()", start);
        int action = source.indexOf(effect, start);
        assertTrue("explicit serial authority gate must precede effect", start >= 0 && gate > start && action > gate);
    }

    private static void assertBoundaryEffectOccurrences(
            String source, String effectName, int expectedOccurrences) {
        String effect = "CustomerActionBoundary.Effect." + effectName;
        assertEquals(effect, expectedOccurrences, occurrences(source, effect));
        boolean[] code = executableCodePositions(source);
        int offset = 0;
        while ((offset = executableIndexOf(source, effect, offset, code)) >= 0) {
            assertTrue(effect + " must be the first argument of a customer boundary",
                    effectIsBoundaryFirstArgument(source, effect, offset));
            offset += effect.length();
        }
    }

    private static void assertWrappedOccurrences(String source, String effect,
            String delegate, int expectedOccurrences) {
        assertEquals(delegate, expectedOccurrences, occurrences(source, delegate));
        boolean[] code = executableCodePositions(source);
        int offset = 0;
        int exactOccurrences = 0;
        while ((offset = executableMarkerIndexOf(
                source, delegate, offset, code)) >= 0) {
            exactOccurrences++;
            assertTrue(delegate + " must be inside a matching " + effect
                            + " boundary invocation as a lazy delegate",
                    markerIsWrappedByEffect(
                            source, effect, offset, delegate.length()));
            offset += delegate.length();
        }
        assertEquals(delegate + " exact executable occurrences",
                expectedOccurrences, exactOccurrences);
    }

    private static void assertAllBoundaryDelegatesAreLazy(
            String source, int expectedOccurrences) {
        java.util.ArrayList<BoundaryInvocation> invocations =
                boundaryInvocations(source);
        assertEquals("customer boundary invocation count",
                expectedOccurrences, invocations.size());
        for (BoundaryInvocation invocation : invocations) {
            assertTrue("customer boundary second argument must be a lazy lambda "
                            + "or safe method reference",
                    isLazyDelegate(source, invocation));
        }
    }

    private static void assertBoundaryFixtureRejected(
            String source, String message) {
        assertBoundaryFixtureRejected(source,
                "CustomerActionBoundary.Effect.DISCOVERY",
                "discoveryCoordinator.start()", message);
    }

    private static void assertBoundaryFixtureRejected(
            String source, String effect, String marker, String message) {
        boolean rejected = false;
        try {
            assertWrappedOccurrences(source, effect, marker, 1);
        } catch (AssertionError expected) {
            rejected = true;
        }
        assertTrue(message, rejected);
    }

    private static void assertExplicitAdminOnlyEnrollmentRouting(
            String source, String directMarker, String boundaryEffect) {
        assertFalse(source.contains("enrollmentOrigin != EnrollmentOrigin.HOME"));
        assertFalse(source.contains("enrollmentOrigin == EnrollmentOrigin.HOME"));
        String adminCondition =
                "if (enrollmentOrigin == EnrollmentOrigin.ADMIN)";
        boolean[] code = executableCodePositions(source);
        int admin = executableMarkerIndexOf(source, adminCondition, 0, code);
        assertTrue("missing explicit ADMIN-only branch", admin >= 0);
        int open = firstExecutableIndex(source,
                admin + adminCondition.length(), source.length(), code);
        assertTrue("ADMIN condition must immediately own a block",
                open >= 0 && source.charAt(open) == '{');
        int close = matchingBrace(source, open);
        int direct = executableMarkerIndexOf(source, directMarker, 0, code);
        assertTrue("missing exact executable direct enrollment action", direct >= 0);
        assertEquals("direct enrollment action must occur exactly once", -1,
                executableMarkerIndexOf(
                        source, directMarker, direct + directMarker.length(), code));
        assertTrue("direct enrollment action must be inside ADMIN branch",
                direct > open && direct < close);
        int boundary = executableMarkerIndexOf(
                source, boundaryEffect, close + 1, code);
        assertTrue("all non-ADMIN origins must reach the customer boundary",
                boundary > close);
    }

    private static void assertEnrollmentRoutingRejected(
            String source, String message) {
        boolean rejected = false;
        try {
            assertExplicitAdminOnlyEnrollmentRouting(source,
                    "beginFaceEnrollmentPreflight();",
                    "CustomerActionBoundary.Effect.RETRY");
        } catch (AssertionError expected) {
            rejected = true;
        }
        assertTrue(message, rejected);
    }

    private static int matchingBrace(String source, int open) {
        assertTrue("missing opening brace", open >= 0);
        boolean[] code = executableCodePositions(source);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            if (!code[index]) continue;
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        fail("unbalanced enrollment ADMIN branch");
        return -1;
    }

    private static void assertWorkerWrappedOccurrences(String source, String effect,
            String delegate, int expectedOccurrences) {
        assertWrappedOccurrences(source, effect, delegate, expectedOccurrences);
        java.util.ArrayList<int[]> executors = invocationRanges(
                source, "RETURN_SERVICE_EXECUTOR.execute(");
        boolean[] code = executableCodePositions(source);
        int offset = 0;
        while ((offset = executableIndexOf(source, delegate, offset, code)) >= 0) {
            boolean wrappedInWorker = false;
            for (BoundaryInvocation boundary : boundaryInvocations(source)) {
                if (offset <= boundary.firstComma
                        || offset >= boundary.delegateEnd
                        || !firstArgument(source, boundary).equals(effect)) {
                    continue;
                }
                for (int[] executor : executors) {
                    if (executor[0] < boundary.openParenthesis
                            && boundary.closeParenthesis < executor[1]) {
                        wrappedInWorker = true;
                        break;
                    }
                }
            }
            assertTrue(delegate
                    + " must recheck the customer boundary inside its executor worker",
                    wrappedInWorker);
            offset += delegate.length();
        }
    }

    private static java.util.ArrayList<int[]> invocationRanges(
            String source, String prefix) {
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();
        boolean[] code = executableCodePositions(source);
        int offset = 0;
        while ((offset = executableIndexOf(source, prefix, offset, code)) >= 0) {
            int open = offset + prefix.length() - 1;
            int close = matchingParenthesis(source, open);
            ranges.add(new int[] {open, close});
            offset = open + 1;
        }
        return ranges;
    }

    private static boolean effectIsBoundaryFirstArgument(
            String source, String effect, int effectIndex) {
        for (BoundaryInvocation invocation : boundaryInvocations(source)) {
            if (effectIndex > invocation.openParenthesis
                    && effectIndex < invocation.firstComma
                    && effectIndex + effect.length() <= invocation.firstComma
                    && firstArgument(source, invocation).equals(effect)) {
                return true;
            }
        }
        return false;
    }

    private static boolean markerIsWrappedByEffect(
            String source, String effect, int markerIndex, int markerLength) {
        boolean[] code = executableCodePositions(source);
        for (BoundaryInvocation invocation : boundaryInvocations(source)) {
            if (markerIndex <= invocation.firstComma
                    || markerIndex + markerLength > invocation.delegateEnd
                    || !isLazyDelegate(source, invocation)
                    || !lazyDelegateContainsMarker(
                            source, invocation, markerIndex, markerLength)) {
                continue;
            }
            int effectIndex = executableIndexOf(
                    source, effect, invocation.openParenthesis + 1, code);
            if (effectIndex > invocation.openParenthesis
                    && effectIndex < invocation.firstComma
                    && effectIndex + effect.length() <= invocation.firstComma
                    && firstArgument(source, invocation).equals(effect)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLazyDelegate(
            String source, BoundaryInvocation invocation) {
        boolean[] code = executableCodePositions(source);
        int start = firstExecutableIndex(
                source, invocation.firstComma + 1, invocation.delegateEnd, code);
        int end = lastExecutableIndex(
                source, start, invocation.delegateEnd, code);
        if (start < 0 || end < start) return false;

        int arrow = executableIndexOf(source, "->", start, code);
        if (arrow >= start && arrow <= end) {
            return compactExecutable(source, start, arrow, code).equals("()");
        }

        int separator = executableIndexOf(source, "::", start, code);
        if (separator < start || separator > end) return false;
        String receiver = compactExecutable(source, start, separator, code);
        String target = compactExecutable(source, separator + 2, end + 1, code);
        return receiver.matches("[A-Za-z_$][A-Za-z0-9_$.]*")
                && target.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    private static boolean lazyDelegateContainsMarker(String source,
            BoundaryInvocation invocation, int markerIndex, int markerLength) {
        boolean[] code = executableCodePositions(source);
        int start = firstExecutableIndex(
                source, invocation.firstComma + 1, invocation.delegateEnd, code);
        int arrow = executableIndexOf(source, "->", start, code);
        if (arrow >= start && arrow < invocation.delegateEnd) {
            return markerIndex >= arrow + 2;
        }
        int separator = executableIndexOf(source, "::", start, code);
        return separator >= start
                && separator < invocation.delegateEnd
                && markerIndex + markerLength > separator + 2;
    }

    private static int firstExecutableIndex(String source, int start, int end,
            boolean[] code) {
        for (int index = Math.max(0, start);
                index < Math.min(end, source.length()); index++) {
            if (code[index] && !Character.isWhitespace(source.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int lastExecutableIndex(String source, int start, int end,
            boolean[] code) {
        for (int index = Math.min(end, source.length()) - 1;
                index >= Math.max(0, start); index--) {
            if (code[index] && !Character.isWhitespace(source.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static String compactExecutable(String source, int start, int end,
            boolean[] code) {
        StringBuilder compact = new StringBuilder();
        for (int index = Math.max(0, start);
                index < Math.min(end, source.length()); index++) {
            if (code[index] && !Character.isWhitespace(source.charAt(index))) {
                compact.append(source.charAt(index));
            }
        }
        return compact.toString();
    }

    private static String firstArgument(
            String source, BoundaryInvocation invocation) {
        return source.substring(
                invocation.openParenthesis + 1, invocation.firstComma).trim();
    }

    private static java.util.ArrayList<BoundaryInvocation> boundaryInvocations(
            String source) {
        java.util.ArrayList<BoundaryInvocation> invocations =
                new java.util.ArrayList<>();
        String[] prefixes = {
                "customerActionBoundary.run(",
                "customerActionBoundary.call("
        };
        boolean[] code = executableCodePositions(source);
        for (String prefix : prefixes) {
            int offset = 0;
            while ((offset = executableIndexOf(source, prefix, offset, code)) >= 0) {
                if (offset > 0
                        && Character.isJavaIdentifierPart(source.charAt(offset - 1))) {
                    offset += prefix.length();
                    continue;
                }
                int open = offset + prefix.length() - 1;
                int close = matchingParenthesis(source, open);
                int[] commas = topLevelCommas(source, open, close);
                assertTrue("customer boundary invocation has no first-argument comma",
                        commas.length >= 1);
                int delegateEnd = commas.length >= 2 ? commas[1] : close;
                invocations.add(new BoundaryInvocation(
                        open, commas[0], delegateEnd, close));
                offset = open + 1;
            }
        }
        return invocations;
    }

    private static boolean[] executableCodePositions(String source) {
        boolean[] code = new boolean[source.length()];
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (quoted || character) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((quoted && current == '"')
                        || (character && current == '\'')) {
                    quoted = false;
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '\'') {
                character = true;
            } else {
                code[index] = true;
            }
        }
        return code;
    }

    private static int executableIndexOf(String source, String token,
            int fromIndex, boolean[] code) {
        int offset = Math.max(0, fromIndex);
        while ((offset = source.indexOf(token, offset)) >= 0) {
            boolean executable = true;
            for (int index = offset; index < offset + token.length(); index++) {
                if (index >= code.length || !code[index]) {
                    executable = false;
                    break;
                }
            }
            if (executable) {
                return offset;
            }
            offset++;
        }
        return -1;
    }

    private static int executableMarkerIndexOf(String source, String marker,
            int fromIndex, boolean[] code) {
        int offset = Math.max(0, fromIndex);
        while ((offset = executableIndexOf(source, marker, offset, code)) >= 0) {
            int end = offset + marker.length();
            int previous = lastExecutableIndex(source, 0, offset, code);
            int next = firstExecutableIndex(source, end, source.length(), code);
            boolean leftBoundary = !Character.isJavaIdentifierPart(
                    marker.charAt(0))
                    || ((offset == 0
                    || !Character.isJavaIdentifierPart(source.charAt(offset - 1)))
                    && (previous < 0 || source.charAt(previous) != '.'));
            boolean rightBoundary = !Character.isJavaIdentifierPart(
                    marker.charAt(marker.length() - 1))
                    || ((end >= source.length()
                    || !Character.isJavaIdentifierPart(source.charAt(end)))
                    && (next < 0 || source.charAt(next) != '.'));
            if (leftBoundary && rightBoundary) {
                return offset;
            }
            offset++;
        }
        return -1;
    }

    private static int matchingParenthesis(String source, int open) {
        int depth = 0;
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (quoted || character) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((quoted && current == '"')
                        || (character && current == '\'')) {
                    quoted = false;
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '\'') {
                character = true;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        fail("unbalanced customer boundary invocation");
        return -1;
    }

    private static int[] topLevelCommas(String source, int open, int close) {
        java.util.ArrayList<Integer> commas = new java.util.ArrayList<>();
        int parenthesisDepth = 0;
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = open + 1; index < close; index++) {
            char current = source.charAt(index);
            char next = index + 1 < close ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (quoted || character) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((quoted && current == '"')
                        || (character && current == '\'')) {
                    quoted = false;
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '\'') {
                character = true;
            } else if (current == '(') {
                parenthesisDepth++;
            } else if (current == ')') {
                parenthesisDepth--;
            } else if (current == '{') {
                braceDepth++;
            } else if (current == '}') {
                braceDepth--;
            } else if (current == '[') {
                bracketDepth++;
            } else if (current == ']') {
                bracketDepth--;
            } else if (current == ','
                    && parenthesisDepth == 0
                    && braceDepth == 0
                    && bracketDepth == 0) {
                commas.add(index);
            }
        }
        int[] result = new int[commas.size()];
        for (int index = 0; index < commas.size(); index++) {
            result[index] = commas.get(index);
        }
        return result;
    }

    private static void assertFirstStatementStartsWith(
            String block, String expectedStart) {
        int body = block.indexOf('{');
        assertTrue("missing block body", body >= 0);
        String first = block.substring(body + 1).trim();
        assertTrue("first statement must start with: " + expectedStart,
                first.startsWith(expectedStart));
    }

    private static void assertFirstIsolatedReadinessGate(String method) {
        int body = method.indexOf('{');
        int guard = method.indexOf("if (!customerActionsEnabled())", body);
        assertTrue("missing isolated readiness guard", guard >= 0);
        for (int index = body + 1; index < guard; index++) {
            assertTrue("readiness guard must be the first statement",
                    Character.isWhitespace(method.charAt(index)));
        }
        assertIsolatedReadinessReturn(method, guard, "return;");
    }

    private static void assertIsolatedFaceReadinessReturn(String method) {
        int guard = method.indexOf("if (!faceActionsEnabled())");
        assertTrue("missing isolated face readiness guard", guard >= 0);
        int guardBody = method.indexOf('{', guard);
        int guardClose = method.indexOf('}', guardBody);
        assertTrue("face readiness guard must have a body", guardBody > guard);
        assertEquals("face readiness guard must contain only the early return",
                "return;", method.substring(guardBody + 1, guardClose).trim());
    }

    private static void assertFirstIsolatedBooleanReadinessGate(String method) {
        int body = method.indexOf('{');
        int guard = method.indexOf("if (!customerActionsEnabled())", body);
        assertTrue("missing isolated readiness guard", guard >= 0);
        for (int index = body + 1; index < guard; index++) {
            assertTrue("readiness guard must be the first statement",
                    Character.isWhitespace(method.charAt(index)));
        }
        assertIsolatedReadinessReturn(method, guard, "return false;");
    }

    private static void assertIsolatedReadinessReturn(
            String source, String guardMarker, String expectedReturn) {
        int guard = source.indexOf(guardMarker);
        assertTrue("missing readiness guard: " + guardMarker, guard >= 0);
        assertIsolatedReadinessReturn(source, guard, expectedReturn);
    }

    private static void assertIsolatedReadinessReturn(
            String source, int guard, String expectedReturn) {
        int guardBody = source.indexOf('{', guard);
        int guardClose = source.indexOf('}', guardBody);
        assertTrue("readiness guard must have a body", guardBody > guard);
        String condition = source.substring(guard, guardBody)
                .replaceAll("\\s+", " ").trim();
        assertTrue("readiness guard must test customer availability",
                condition.contains("!customerActionsEnabled()"));
        assertEquals("readiness guard must contain only the early return",
                expectedReturn, source.substring(guardBody + 1, guardClose).trim());
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("missing first marker: " + first, firstIndex >= 0);
        assertTrue("missing second marker: " + second, secondIndex >= 0);
        assertTrue(first + " must precede " + second, firstIndex < secondIndex);
    }

    private static String slice(String source, String marker, String next) {
        int start = source.indexOf(marker);
        if (start < 0) fail("missing marker: " + marker);
        int end = source.indexOf(next, start + marker.length());
        if (end < 0) fail("missing end marker: " + next);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        boolean[] code = executableCodePositions(source);
        int offset = 0;
        while ((offset = executableIndexOf(source, token, offset, code)) >= 0) {
            count++;
            offset += token.length();
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

    private static final class CountingCredentialPolicy
            implements CredentialAdmissionPolicy {
        private int calls;

        @Override
        public CredentialAdmission admit(UnlockMethod method, String rawCredential) {
            calls++;
            return CredentialAdmission.accepted(method);
        }
    }

    private static final class CountingLayoutPolicy implements InitialLayoutPolicy {
        private int calls;

        @Override
        public LockerLayoutSnapshot initialLayout() {
            calls++;
            return null;
        }

        @Override
        public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
            calls++;
            return null;
        }
    }

    @FunctionalInterface
    private interface BooleanEffect {
        boolean run();
    }

    private static final class CountingCustomerEffects {
        private final int[] calls =
                new int[CustomerActionBoundary.Effect.values().length];

        private boolean booleanEffect(
                CustomerActionBoundary.Effect point, BooleanEffect effect) {
            calls[point.ordinal()]++;
            return effect.run();
        }

        private void voidEffect(CustomerActionBoundary.Effect point) {
            calls[point.ordinal()]++;
        }

        private boolean booleanValueEffect(CustomerActionBoundary.Effect point) {
            calls[point.ordinal()]++;
            return true;
        }

        private long longValueEffect(CustomerActionBoundary.Effect point) {
            calls[point.ordinal()]++;
            return 41L;
        }

        private Object objectValueEffect(CustomerActionBoundary.Effect point) {
            calls[point.ordinal()]++;
            return new Object();
        }

        private void assertNoCalls() {
            for (CustomerActionBoundary.Effect point
                    : CustomerActionBoundary.Effect.values()) {
                assertEquals(point.name(), 0, calls[point.ordinal()]);
            }
        }
    }

    private static final class BoundaryInvocation {
        private final int openParenthesis;
        private final int firstComma;
        private final int delegateEnd;
        private final int closeParenthesis;

        private BoundaryInvocation(
                int openParenthesis, int firstComma,
                int delegateEnd, int closeParenthesis) {
            this.openParenthesis = openParenthesis;
            this.firstComma = firstComma;
            this.delegateEnd = delegateEnd;
            this.closeParenthesis = closeParenthesis;
        }
    }
}
