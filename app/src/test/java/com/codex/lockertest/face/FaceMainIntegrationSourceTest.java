package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source-boundary RED tests for the Android-only MainActivity face wiring. */
public final class FaceMainIntegrationSourceTest {
    private static final String MAIN =
            "app/src/main/java/com/codex/lockertest/MainActivity.java";
    private static final String CONTROLLER =
            "app/src/main/java/com/codex/lockertest/face/FaceRecognitionController.java";

    @Test
    public void faceRouteBuildsTheDedicatedViewAndBindsItsRealPreviewHolder()
            throws Exception {
        String source = read(MAIN);
        String home = javaMethodBody(source, "createHomeView");
        assertContains(home, "void onFaceRequested()");
        assertContains(home, "beginFaceRecognition()");
        assertFalse(home.contains("openUnavailable(UnlockMethod.FACE)"));

        String render = javaMethodBody(source, "renderScreen");
        assertOrdered(render, "case FACE_RECOGNITION:",
                "renderFaceRecognition()", "break;");
        String face = javaMethodBody(source, "renderFaceRecognition");
        assertContains(face, "new FaceRecognitionView(this)");
        assertContains(face, "faceRecognitionView = sourceView");
        assertContains(face, "sourceView.previewHolder()");

        String start = javaMethodBody(source, "startFaceController");
        assertContains(start,
                "flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION");
        assertContains(start, "faceRecognitionView == sourceView");
        assertContains(start, "checkSelfPermission(Manifest.permission.CAMERA)");
        assertOrdered(start, "checkSelfPermission(Manifest.permission.CAMERA)",
                "PackageManager.PERMISSION_GRANTED", "expectedController.start()");
    }

    @Test
    public void cameraPermissionIsRequestedOnceAndItsCallbackRechecksWithoutLooping()
            throws Exception {
        String source = read(MAIN);
        String request = javaMethodBody(source, "requestFaceCameraPermission");
        assertContains(request, "active");
        assertContains(request,
                "flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION");
        assertContains(request, "faceRecognitionView == sourceView");
        assertContains(request, "checkSelfPermission(Manifest.permission.CAMERA)");
        assertEquals(1, occurrences(request, "requestPermissions("));

        String result = javaMethodBody(source, "onRequestPermissionsResult");
        assertContains(result, "requestCode == FACE_CAMERA_PERMISSION_REQUEST");
        assertContains(result, "checkSelfPermission(Manifest.permission.CAMERA)");
        assertContains(result, "PackageManager.PERMISSION_GRANTED");
        assertContains(result, "startFaceController(");
        assertFalse("permission callback must not reopen the system dialog",
                result.contains("requestPermissions("));
    }

    @Test
    public void everyFaceControllerCallbackPostsBeforeTheSixCurrentAttemptGuards()
            throws Exception {
        String source = read(MAIN);
        String create = javaMethodBody(source, "createFaceController");
        assertEquals("state, quality and terminal callbacks must share one boundary",
                3, occurrences(create, "postFaceCallback("));

        String post = javaMethodBody(source, "postFaceCallback");
        assertOrdered(post, "handler.post(", "isCurrentFaceCallback(",
                "action.run()");
        String gate = javaMethodBody(source, "isCurrentFaceCallback");
        assertContains(gate, "active");
        assertContains(gate,
                "flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION");
        assertContains(gate, "faceGeneration == expectedFaceGeneration");
        assertContains(gate, "faceRecognitionView == expectedView");
        assertContains(gate, "faceController == expectedController");
        assertContains(gate, "activeFaceSessionId == expectedSessionId");
    }

    @Test
    public void completedResultBackIsIgnoredBeforeAnyCancellationOrFlowMutation()
            throws Exception {
        String back = javaMethodBody(read(MAIN), "onBackPressed");
        assertOrdered(back,
                "priorScreen == KioskFlowModel.Screen.RESULT",
                "priorContext == KioskFlowModel.ResultContext.NONE",
                "return;",
                "cancelFaceWorkAndInvalidate()",
                "invalidateCustomerWork(true)",
                "flow.back()");
    }

    @Test
    public void everyFaceExitDetachesOnUiBeforeSerialExecutorCleanup()
            throws Exception {
        String source = read(MAIN);
        String cancel = javaMethodBody(source, "cancelFaceWorkAndInvalidate");
        assertOrdered(cancel,
                "faceGeneration = nextGeneration(faceGeneration)",
                "activeFaceSessionId = 0L",
                "clearFacePermissionRequest()",
                "FaceRecognitionController controller = faceController",
                "faceController = null",
                "faceRecognitionView = null",
                "enqueueFaceControllerCleanup(controller)");
        assertFalse("UI invalidation must never wait in controller.cancel()",
                cancel.contains("controller.cancel("));
        assertFalse("UI invalidation must never wait in controller.close()",
                cancel.contains("controller.close("));

        assertContains(source,
                "private static final ExecutorService FACE_CLEANUP_EXECUTOR");
        String enqueue = javaMethodBody(source, "enqueueFaceControllerCleanup");
        assertContains(enqueue, "FACE_CLEANUP_EXECUTOR.execute(");
        String cleanup = javaMethodBody(source, "runFaceControllerCleanup");
        assertOrdered(cleanup, "controller.cancel()", "controller.close()");

        assertOrdered(javaMethodBody(source, "onBackPressed"),
                "cancelFaceWorkAndInvalidate()", "flow.back()");
        assertContains(javaMethodBody(source, "returnHome"),
                "cancelFaceWorkAndInvalidate()");
        assertContains(javaMethodBody(source, "showAdminPin"),
                "cancelFaceWorkAndInvalidate()");
        assertOrdered(javaMethodBody(source, "onStop"),
                "cancelFaceWorkAndInvalidate()", "super.onStop()");
        assertOrdered(javaMethodBody(source, "onDestroy"),
                "cancelFaceWorkAndInvalidate()", "super.onDestroy()");
    }

    @Test
    public void controllerAndCameraCreationWaitForTheSharedCleanupBarrier()
            throws Exception {
        String source = read(MAIN);
        assertContains(source,
                "FACE_CLEANUP_BARRIER_TIMEOUT_MILLIS = 15_000L");

        String render = javaMethodBody(source, "renderFaceRecognition");
        assertContains(render, "awaitFaceCleanupBarrier(");
        assertFalse("render must not construct a second camera before cleanup",
                render.contains("createFaceController("));

        String await = javaMethodBody(source, "awaitFaceCleanupBarrier");
        assertOrdered(await,
                "handler.postDelayed(",
                "FACE_CLEANUP_BARRIER_TIMEOUT_MILLIS",
                "FACE_CLEANUP_EXECUTOR.execute(",
                "handler.post(");
        String passed = javaMethodBody(source, "completeFaceCleanupBarrier");
        assertOrdered(passed,
                "isCurrentFaceBarrier(",
                "clearFaceCleanupBarrier()",
                "createFaceController(",
                "startFaceController(");

        String timeout = javaMethodBody(source, "failFaceCleanupBarrier");
        assertContains(timeout, "isCurrentFaceBarrier(");
        assertContains(timeout, "sourceView.showFailure(");
        assertFalse("timeout must fail closed without opening Camera",
                timeout.contains("createFaceController("));
    }

    @Test
    public void cleanupExceptionPoisonsTheProcessBeforeAnyLaterCameraCreation()
            throws Exception {
        String source = read(MAIN);
        String cleanup = javaMethodBody(source, "runFaceControllerCleanup");
        assertEquals("cancel and close failures must each poison the process",
                2, occurrences(cleanup, "faceCleanupUnavailable = true"));
        assertOrdered(cleanup,
                "controller.cancel()",
                "catch (RuntimeException | LinkageError ignored)",
                "faceCleanupUnavailable = true",
                "controller.close()",
                "catch (RuntimeException | LinkageError ignored)",
                "faceCleanupUnavailable = true");

        String passed = javaMethodBody(source, "completeFaceCleanupBarrier");
        assertOrdered(passed,
                "clearFaceCleanupBarrier()",
                "if (faceCleanupUnavailable || faceSubsystem == null)",
                "sourceView.showFailure(",
                "return;",
                "createFaceController(",
                "startFaceController(");
    }

    @Test
    public void terminalAndPermissionFailuresRebuildAfterAsyncInvalidation()
            throws Exception {
        String source = read(MAIN);
        String terminal = javaMethodBody(source, "renderFaceTerminal");
        assertContains(terminal, "showFaceFailure(");
        assertContains(terminal, "showFacePermissionFailure(");
        assertFalse(terminal.contains("controller.cancel("));
        assertFalse(terminal.contains("controller.close("));

        String failure = javaMethodBody(source, "showFaceFailure");
        assertOrdered(failure,
                "setFaceDeferredFailure(",
                "renderFaceRecognition()");
        String permissionResult =
                javaMethodBody(source, "onRequestPermissionsResult");
        assertContains(permissionResult, "showFacePermissionFailure(");

        String render = javaMethodBody(source, "renderFaceRecognition");
        assertOrdered(render,
                "cancelFaceWorkAndInvalidate()",
                "new FaceRecognitionView(this)",
                "sourceView.show");
    }

    @Test
    public void stoppedFaceAttemptReturnsHomeInsteadOfImplicitlyReopeningCamera()
            throws Exception {
        String start = javaMethodBody(read(MAIN), "onStart");
        assertContains(start,
                "flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION");
        assertContains(start, "returnHome(\"人脸识别已中断，请重新选择\")");
        assertFalse("resume must require another explicit face click",
                start.contains("renderScreen()"));
    }

    @Test
    public void faceSuccessReleasesBeforeAcceptingRenderingAndDiscovering()
            throws Exception {
        String source = read(MAIN);
        String success = javaMethodBody(source, "completeFaceRecognition");
        assertOrdered(success,
                "cancelFaceWorkAndInvalidate()",
                "flow.acceptFaceVerification(result,",
                "renderScreen()",
                "startDiscoveryOperation()");
        assertContains(success, "if (!flow.acceptFaceVerification(result,");
        assertFalse(success.contains("submitLocker("));
        assertFalse(success.contains("unlockCoordinator.start("));
        assertFalse(success.contains("customerSerialTransmitter.send("));
        assertFalse(success.contains("gateway.send("));
    }

    @Test
    public void expiryAndNineArgumentAuthorizationFailClosedBeforeUnlock()
            throws Exception {
        String source = read(MAIN);
        String submit = javaMethodBody(source, "submitLocker");
        assertContains(submit, "flow.confirmLockerAt(target,");
        assertFalse(submit.contains("flow.confirmLocker(target)"));
        assertContains(submit, "KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED");
        assertContains(submit, "验证已过期，请重新识别");
        assertOrdered(submit,
                "KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED",
                "invalidateCustomerWork(true)",
                "renderScreen()",
                "customerUnlockAuthorizer.authorize(",
                "unlockCoordinator.startAuthorized(");

        String authorize = invocation(source, "customerUnlockAuthorizer.authorize(");
        assertEquals("FACE authorization must use the full authoritative contract",
                9, argumentCount(authorize));
        assertContains(authorize, "faceSuccess");
        assertContains(authorize, "expectedRequestId()");
        assertContains(authorize, "faceSubsystem.deviceBinding()");
        assertContains(authorize, "faceSubsystem.processBinding()");
        assertContains(authorize, "faceSubsystem.verificationEnvironment()");
    }

    @Test
    public void administratorAuthenticationShowsChooserAndFaceAdminNeverTouchesSerial()
            throws Exception {
        String source = read(MAIN);
        String pin = javaMethodBody(source, "renderAdminPin");
        assertContains(pin, "void onAdminAuthenticated()");
        assertContains(pin, "renderAdminFunctions()");
        assertFalse(pin.contains("launchAdminActivity()"));

        String chooser = javaMethodBody(source, "renderAdminFunctions");
        assertContains(chooser, "new AdminFunctionOverlay(this)");
        assertContains(chooser, "void onSerialRequested()");
        assertContains(chooser, "launchAdminActivity()");
        assertContains(chooser, "void onFaceSdkRequested()");
        assertContains(chooser, "launchFaceSdkAdminActivity()");
        assertContains(chooser, "void onHomeRequested()");

        String faceAdmin = javaMethodBody(source, "launchFaceSdkAdminActivity");
        assertContains(faceAdmin, "FaceSdkAdminActivity.class");
        assertFalse(faceAdmin.contains("SERIAL_GATEWAY_OWNER"));
        assertFalse(faceAdmin.contains("SerialGateway"));
        assertFalse(faceAdmin.contains("adminHandoffGate"));
        assertFalse(faceAdmin.contains("closePort"));
    }

    @Test
    public void faceHasZeroWritesAndDiscoveryAndUnlockUseExactTask10Attribution()
            throws Exception {
        String source = read(MAIN);
        String begin = javaMethodBody(source, "beginFaceRecognition");
        assertContains(begin, "CustomerSerialPhase.FACE_PRE_SELECTION");
        assertFalse(begin.contains(".send("));
        assertFalse(javaMethodBody(source, "renderFaceRecognition").contains(".send("));
        assertFalse(javaMethodBody(source, "postFaceCallback").contains(".send("));

        String discovery = javaMethodBody(source, "startDiscoveryOperation");
        assertContains(discovery, "CustomerSerialPhase.LOCKER_DISCOVERY");
        assertContains(discovery, "SerialWriteAttribution.discovery(");
        assertContains(discovery, "customerSerialTransmitter.beginOperation(");

        String submit = javaMethodBody(source, "submitLocker");
        assertContains(submit, "CustomerSerialPhase.LOCKER_CONFIRMED");
        assertContains(submit, "SerialWriteAttribution.unlock(target)");
        assertContains(submit, "customerConfirmedTarget = target");
        assertOrdered(submit, "customerConfirmedTarget = target",
                "customerSerialTransmitter.beginOperation(",
                "unlockCoordinator.startAuthorized(");
    }

    @Test
    public void customerWritesHaveOneLeaseBoundWriterAndInvalidationEndsTheOldOperation()
            throws Exception {
        String source = read(MAIN);
        assertContains(source, "private final CustomerSerialTransmitter customerSerialTransmitter");
        assertContains(source, "this::writeAuthorizedCustomerPayload");
        assertEquals("Main may have exactly one physical customer send site",
                1, occurrences(source, "gateway.send("));

        String send = javaMethodBody(source, "sendForOperation");
        assertContains(send, "customerSerialTransmitter.send(");
        assertFalse(send.contains("gateway.send("));
        String writer = javaMethodBody(source, "writeAuthorizedCustomerPayload");
        assertContains(writer, "gateway.send(");
        assertContains(writer, "gatewayGate.accepts(customerGatewayGeneration)");
        assertContains(writer, "gateway.phase() == SerialSessionState.Phase.OPEN");
        assertContains(writer, "SerialConfig.defaults().equals(gateway.getActiveConfig())");

        String invalidate = javaMethodBody(source, "invalidateCustomerWork");
        assertOrdered(invalidate,
                "long endingOperationToken = customerOperationToken",
                "gatewayGate.invalidate()",
                "customerSerialTransmitter.endOperation(endingOperationToken)",
                "advanceCustomerOperationToken()");
        String advance = javaMethodBody(source, "advanceCustomerOperationToken");
        assertContains(advance, "customerOperationToken == Long.MAX_VALUE");
        assertFalse(advance.contains("customerOperationToken = 1L"));
    }

    @Test
    public void faceSourcesArePrivateAndControllerIsPureJava()
            throws Exception {
        String main = read(MAIN);
        String controller = read(CONTROLLER);
        String combined = main + controller;
        Pattern activationShape = Pattern.compile(
                "(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])");
        assertFalse(activationShape.matcher(combined).find());
        for (String prohibited : new String[] {
                "Base64", "FileOutputStream", "FileWriter", "MediaStore",
                "getCacheDir", "getExternalStorage", "printStackTrace",
                "getMessage()", "android.util.Log", "System.out", "System.err"
        }) {
            assertFalse("prohibited face data/logging token: " + prohibited,
                    combined.contains(prohibited));
        }
        assertFalse(controller.contains("import android."));
        assertFalse(controller.contains("android."));
        assertFalse(controller.contains("MainActivity"));
        assertFalse(controller.contains("SerialGateway"));
        assertFalse(controller.contains("CustomerSerialTransmitter"));
        assertFalse(controller.contains("java.io.File"));
    }

    @Test
    public void malformedVendorAnalysisAndPreviewGeometryFailClosedWithoutUiCrash()
            throws Exception {
        String source = read(MAIN);
        String create = javaMethodBody(source, "createFaceController");
        assertOrdered(create,
                "if (analysis == null)",
                "callback.onFailure(",
                "return;",
                "analysis.observation()");

        String quality = javaMethodBody(source, "renderFaceQuality");
        assertContains(quality, "safeFaceBox(event.previewBox())");
        String safeBox = javaMethodBody(source, "safeFaceBox");
        assertContains(safeBox, "Float.isNaN(");
        assertContains(safeBox, "Float.isInfinite(");
        assertContains(safeBox, "return null;");
    }

    private static String invocation(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0) fail("missing invocation: " + marker);
        int open = source.indexOf('(', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '(') depth++;
            if (value == ')' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("unterminated invocation: " + marker);
    }

    private static int argumentCount(String invocation) {
        int open = invocation.indexOf('(');
        int close = invocation.lastIndexOf(')');
        if (open < 0 || close <= open) return 0;
        String arguments = invocation.substring(open + 1, close).trim();
        if (arguments.isEmpty()) return 0;
        int count = 1;
        int depth = 0;
        for (int index = 0; index < arguments.length(); index++) {
            char value = arguments.charAt(index);
            if (value == '(' || value == '[' || value == '{') depth++;
            if (value == ')' || value == ']' || value == '}') depth--;
            if (value == ',' && depth == 0) count++;
        }
        return count;
    }

    private static String javaMethodBody(String source, String methodName) {
        Pattern declaration = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)\\s+"
                        + "(?:(?:static|final|synchronized)\\s+)*"
                        + "[A-Za-z0-9_$.<>?,\\[\\] ]+\\s+"
                        + Pattern.quote(methodName) + "\\s*\\(");
        java.util.regex.Matcher matcher = declaration.matcher(source);
        if (!matcher.find()) fail("missing method: " + methodName);
        int open = source.indexOf('{', matcher.end());
        if (open < 0) fail("missing method body: " + methodName);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static void assertOrdered(String source, String... tokens) {
        int position = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, position + 1);
            assertTrue("missing/out-of-order token: " + token, found >= 0);
            position = found;
        }
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

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static String read(String relative) throws IOException {
        Path path = projectRoot().resolve(relative);
        assertTrue("missing source file: " + relative, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
}
