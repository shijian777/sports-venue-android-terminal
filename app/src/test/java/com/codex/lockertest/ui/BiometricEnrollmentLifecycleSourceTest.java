package com.codex.lockertest.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BiometricEnrollmentLifecycleSourceTest {
    @Test
    public void page55And57UseOpaqueNativePanelsAndExactDesignHitRegions()
            throws Exception {
        String choice = readMain(
                "com/codex/lockertest/ui/BiometricEnrollmentChoiceView.java");
        assertContainsAll(choice,
                "ZipScreenAsset.ENROLLMENT_CHOICE",
                "ZipScreenAsset.PALM_ENROLLMENT_UNAVAILABLE",
                "place(content, nativePanel, 20, 105, 1240, 605)",
                "place(content, faceCard, 285, 260, 330, 240)",
                "place(overlay, faceButton, 335, 405, 230, 55)",
                "place(content, palmCard, 665, 260, 330, 240)",
                "place(overlay, palmButton, 715, 405, 230, 55)",
                "place(overlay, choiceBackButton, 550, 550, 180, 48)",
                "place(overlay, palmBackButton, 1050, 110, 176, 38)",
                "ZipKioskShell.unit(context, value)");
        assertEquals(1, count(choice,
                "place(overlay, choiceBackButton, 550, 550, 180, 48)"));
        assertEquals(1, count(choice,
                "place(overlay, palmBackButton, 1050, 110, 176, 38)"));
        assertContainsAll(slice(choice, "public void showChoice()",
                        "public void showBlocker("),
                "showChoiceBack(true)", "showPalmBack(false)");
        assertContainsAll(slice(choice, "public void showBlocker(",
                        "public void showPalmUnavailable()"),
                "showChoiceBack(true)", "showPalmBack(false)");
        assertContainsAll(slice(choice, "public void showPalmUnavailable()",
                        "private void showChoiceBack("),
                "showChoiceBack(false)", "showPalmBack(true)");
        assertFalse(choice.contains("Color.TRANSPARENT"));
    }

    @Test
    public void page56HasOneRealSurfaceAndStatusDoesNotOverlapIt() throws Exception {
        String face = readMain(
                "com/codex/lockertest/ui/FaceEnrollmentView.java");
        assertContainsAll(face,
                "new SurfaceView(context)",
                "place(content, preview, 270, 185, 740, 310)",
                "place(overlay, statusPanel, 405, 500, 470, 78)",
                "place(overlay, backButton, 1050, 110, 176, 38)",
                "人脸采集完成，等待接入服务器提交",
                "人脸画面预览区域",
                "重新尝试人脸采集");
        assertTrue(count(face, "new SurfaceView(context)") == 1);
    }

    @Test
    public void enrollmentHitRegionsMapExactlyAtReferenceAndLetterboxedViewports() {
        assertMappedRect(1280, 800, 20, 105, 1240, 605,
                20, 105, 1240, 605);
        assertMappedRect(1280, 800, 285, 260, 330, 240,
                285, 260, 330, 240);
        assertMappedRect(1280, 800, 335, 405, 230, 55,
                335, 405, 230, 55);
        assertMappedRect(1280, 800, 665, 260, 330, 240,
                665, 260, 330, 240);
        assertMappedRect(1280, 800, 715, 405, 230, 55,
                715, 405, 230, 55);
        assertMappedRect(1280, 800, 550, 550, 180, 48,
                550, 550, 180, 48);
        assertMappedRect(1280, 800, 1050, 110, 176, 38,
                1050, 110, 176, 38);
        assertMappedRect(1280, 800, 270, 185, 740, 310,
                270, 185, 740, 310);
        assertMappedRect(1280, 800, 405, 500, 470, 78,
                405, 500, 470, 78);
        assertMappedRect(1280, 800, 1050, 110, 176, 38,
                1050, 110, 176, 38);

        assertMappedRect(1366, 768, 20, 105, 1240, 605,
                87, 101, 1190, 581);
        assertMappedRect(1366, 768, 285, 260, 330, 240,
                342, 250, 317, 230);
        assertMappedRect(1366, 768, 335, 405, 230, 55,
                390, 389, 221, 53);
        assertMappedRect(1366, 768, 665, 260, 330, 240,
                706, 250, 317, 230);
        assertMappedRect(1366, 768, 715, 405, 230, 55,
                754, 389, 221, 53);
        assertMappedRect(1366, 768, 550, 550, 180, 48,
                596, 528, 173, 46);
        assertMappedRect(1366, 768, 1050, 110, 176, 38,
                1076, 106, 169, 36);
        assertMappedRect(1366, 768, 270, 185, 740, 310,
                327, 178, 710, 298);
        assertMappedRect(1366, 768, 405, 500, 470, 78,
                457, 480, 451, 75);
        assertMappedRect(1366, 768, 1050, 110, 176, 38,
                1076, 106, 169, 36);
    }

    @Test
    public void homeAndAdminButtonsOpenARealTypedEnrollmentRoute() throws Exception {
        String home = readMain(
                "com/codex/lockertest/ui/ZipHomeView.java");
        String admin = readMain(
                "com/codex/lockertest/ui/AdminFunctionOverlay.java");
        String main = readMain("com/codex/lockertest/MainActivity.java");
        assertContainsAll(home,
                "default void onEnrollmentRequested()",
                "掌纹录入",
                "FinalHomeActions",
                "current.onEnrollmentRequested()",
                "palmEnrollmentVisible()");
        assertContainsAll(admin,
                "void onEnrollmentRequested()",
                "view -> callEnrollment()",
                "current.onEnrollmentRequested()");
        assertContainsAll(main,
                "EnrollmentOrigin.HOME",
                "EnrollmentOrigin.ADMIN",
                "renderEnrollmentChoice",
                "returnFromEnrollment");
        assertFalse(main.contains("人脸/掌纹录入功能正在接入"));
    }

    @Test
    public void enrollmentUsesDistinctGenerationAndSharedPoisonedCleanupLane()
            throws Exception {
        String main = readMain("com/codex/lockertest/MainActivity.java");
        assertContainsAll(main,
                "enrollmentGeneration",
                "isCurrentEnrollmentCallback",
                "cancelEnrollmentWorkAndInvalidate",
                "FACE_CLEANUP_EXECUTOR.execute",
                "faceCleanupUnavailable",
                "awaitEnrollmentCleanupBarrier",
                "onStop()",
                "onDestroy()",
                "CAMERA_PERMISSION_REQUEST");
        int cancel = main.indexOf("private void cancelEnrollmentWorkAndInvalidate()");
        int invalidate = main.indexOf("enrollmentGeneration = nextGeneration", cancel);
        int enqueue = main.indexOf("FACE_CLEANUP_EXECUTOR", cancel);
        assertTrue(cancel >= 0 && invalidate > cancel && enqueue > invalidate);

        String acquire = slice(main,
                "private void startEnrollmentCameraAcquire(",
                "private void awaitEnrollmentCleanupBarrier(");
        String afterBarrier = slice(main,
                "private void completeEnrollmentCleanupBarrier(",
                "private FaceEnrollmentCaptureController createEnrollmentCaptureController(");
        assertFalse(acquire.contains("enrollmentSessionGate.begin()"));
        int barrierCleared = afterBarrier.indexOf("clearEnrollmentCleanupBarrier()");
        int sensitiveBegin = afterBarrier.indexOf("enrollmentSessionGate.begin()");
        int controllerCreate = afterBarrier.indexOf("createEnrollmentCaptureController(");
        assertTrue(barrierCleared >= 0 && sensitiveBegin > barrierCleared
                && controllerCreate > sensitiveBegin);
    }

    @Test
    public void terminalFailureRevokesOwnershipBeforePresentingRetryAndLateReadyCannotForward()
            throws Exception {
        String main = readMain("com/codex/lockertest/MainActivity.java");
        String failure = slice(main,
                "private void transitionEnrollmentToSafeFailure(",
                "private void postEnrollmentCallback(");
        assertContainsAll(failure,
                "isCurrentEnrollmentCallback(",
                "enrollmentGeneration = nextGeneration(enrollmentGeneration)",
                "activeEnrollmentSessionId = 0L",
                "enrollmentCaptureController = null",
                "enrollmentSensitiveGeneration = 0L",
                "enrollmentSessionGate.invalidate(sensitiveGeneration)",
                "bindEnrollmentFailureActions(sourceView, failureGeneration)",
                "enqueueEnrollmentControllerCleanup(controller, sensitiveGeneration)",
                "sourceView.showFailure(safeMessage, retryable)");
        assertOrdered(failure,
                "enrollmentGeneration = nextGeneration(enrollmentGeneration)",
                "activeEnrollmentSessionId = 0L",
                "enrollmentCaptureController = null",
                "enrollmentSensitiveGeneration = 0L",
                "enrollmentSessionGate.invalidate(sensitiveGeneration)",
                "bindEnrollmentFailureActions(sourceView, failureGeneration)",
                "enqueueEnrollmentControllerCleanup(controller, sensitiveGeneration)",
                "sourceView.showFailure(safeMessage, retryable)");

        String controller = readMain(
                "com/codex/lockertest/face/FaceEnrollmentCaptureController.java");
        assertContainsAll(controller,
                "if (session.onCameraReady(sessionId))",
                "session.activeSessionId() == sessionId",
                "== FaceCaptureSession.State.PREPARING",
                "safelyCameraReady(sessionId)");
        assertFalse(controller.contains(
                "session.onCameraReady(sessionId);\n                            safelyCameraReady(sessionId);"));
    }

    @Test
    public void enrollmentPreflightAndCaptureUseLocalSdkWithoutServerAuthorization()
            throws Exception {
        String main = readMain("com/codex/lockertest/MainActivity.java");
        String controller = readMain(
                "com/codex/lockertest/face/FaceEnrollmentCaptureController.java");
        assertContainsAll(main,
                "isNetworkOnline()",
                "FaceLicenseStateMachine.State.READY",
                "FaceRuntimeStateMachine.State.READY",
                "Manifest.permission.CAMERA",
                "Camera1FaceCameraController",
                "faceSubsystem.runtime().analyze",
                "new FaceFrameQualityGate()",
                "new AndroidFaceJpegEncoder()",
                "FaceLivenessControl.Capability.SUPPORTED",
                "enrollmentSessionGate.acceptOwnedBuffer",
                "enrollmentSessionGate.invalidate",
                "enrollmentSessionGate.clearInvalidated");
        assertContainsAll(controller,
                "runtime.analyze",
                "new Camera1FaceCameraController()",
                "new AndroidFaceJpegEncoder()",
                "borrowedJpeg.jpeg()",
                "Arrays.copyOf(source, source.length)",
                "listener.onCaptureCompleted(sessionId, demoCopy)",
                "return handle;");
        assertFalse(main.contains("enrollmentVerificationTicket"));
        assertFalse(main.contains("enrollmentClient"));
        assertFalse(main.contains("uploadEnrollment"));
        assertFalse(controller.contains("FaceVerificationClient"));
        assertFalse(controller.contains("verificationTicket"));
        assertFalse(controller.contains("handle == null ? NO_OP"));
    }

    @Test
    public void noEnrollmentSourcePersistsLogsOrClaimsServerSuccess() throws Exception {
        String combined = readMain(
                "com/codex/lockertest/ui/BiometricEnrollmentChoiceView.java")
                + readMain(
                "com/codex/lockertest/ui/FaceEnrollmentView.java")
                + readMain(
                "com/codex/lockertest/ui/zip/BiometricEnrollmentScreenRouter.java")
                + readMain(
                "com/codex/lockertest/face/FaceEnrollmentCaptureController.java");
        for (String forbidden : new String[] {
                "SharedPreferences", "FileOutputStream", "Base64",
                "Log.", "录入成功", "已登记", "http://", "https://"
        }) {
            assertFalse("forbidden enrollment source token: " + forbidden,
                    combined.contains(forbidden));
        }


        String main = readMain("com/codex/lockertest/MainActivity.java");
        String enrollmentEntry = slice(main,
                "private void beginEnrollment(",
                "private void beginFaceRecognition(");
        String enrollmentCleanup = slice(main,
                "private void cancelEnrollmentWorkAndInvalidate()",
                "private void cancelFaceWorkAndInvalidate()");
        String enrollmentMain = enrollmentEntry + enrollmentCleanup;
        for (String forbidden : new String[] {
                "SharedPreferences", "FileOutputStream", "Base64", "Log.",
                "verificationClient", "FaceVerificationResult", "http://", "https://"
        }) {
            assertFalse("forbidden enrollment MainActivity token: " + forbidden,
                    enrollmentMain.contains(forbidden));
        }
    }

    private static void assertContainsAll(String source, String... snippets) {
        for (String snippet : snippets) {
            assertTrue("missing source contract: " + snippet,
                    source.contains(snippet));
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String slice(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        assertTrue("missing source slice start: " + start, startIndex >= 0);
        assertTrue("missing source slice end: " + end, endIndex > startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static void assertOrdered(String source, String... snippets) {
        int previous = -1;
        for (String snippet : snippets) {
            int current = source.indexOf(snippet, previous + 1);
            assertTrue("missing or out-of-order source contract: " + snippet,
                    current > previous);
            previous = current;
        }
    }

    private static void assertMappedRect(int viewportWidth, int viewportHeight,
            int designLeft, int designTop, int designWidth, int designHeight,
            int expectedLeft, int expectedTop, int expectedWidth, int expectedHeight) {
        int canvasWidth = ZipDesignMetrics.px(viewportWidth, viewportHeight, 1280);
        int horizontalLetterbox = (viewportWidth - canvasWidth) / 2;
        assertEquals(expectedLeft, horizontalLetterbox + ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designLeft));
        assertEquals(expectedTop, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designTop));
        assertEquals(expectedWidth, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designWidth));
        assertEquals(expectedHeight, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designHeight));
    }

    private static String readMain(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java").resolve(relative)), StandardCharsets.UTF_8);
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
