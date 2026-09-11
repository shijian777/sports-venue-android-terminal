package com.codex.lockertest.face;

import com.codex.lockertest.face.FaceRecognitionController.Analysis;
import com.codex.lockertest.face.FaceRecognitionController.DiagnosticStage;
import com.codex.lockertest.face.FaceRecognitionController.FailureReason;
import com.codex.lockertest.face.FaceRecognitionController.PreviewBox;
import com.codex.lockertest.face.FaceRecognitionController.Success;
import com.codex.lockertest.face.FaceRecognitionController.TerminalOutcome;
import com.codex.lockertest.face.verification.FaceVerificationClient;
import com.codex.lockertest.face.verification.FaceVerificationRequest;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationSource;
import com.codex.lockertest.face.verification.FaceVerificationStatus;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pure-JVM contract tests for the customer face page orchestration boundary. */
public final class FaceRecognitionControllerTest {
    private static final String DEVICE = "device-binding-a";
    private static final String PROCESS = "process-binding-a";

    @Test
    public void happyPathPreservesExactOrderAndPublishesIndependentSuccessAfterCleanup() {
        Fixture fixture = new Fixture();
        fixture.permission.granted = false;

        long sessionId = fixture.controller.start();
        assertTrue(sessionId > 0L);
        assertEquals(FaceCaptureSession.State.PREPARING, fixture.controller.state());
        assertEquals(1, fixture.license.attempts.size());
        assertEquals(0, fixture.runtime.initializations.size());
        assertEquals(0, fixture.permission.requests.size());
        assertEquals(0, fixture.camera.attempts.size());

        fixture.license.latest().ready();
        assertEquals(1, fixture.runtime.initializations.size());
        assertEquals(0, fixture.permission.requests.size());
        fixture.runtime.latestInitialization().ready();
        assertEquals(1, fixture.permission.requests.size());
        assertEquals(0, fixture.camera.attempts.size());
        fixture.permission.latest().respond(true, false);
        assertEquals(1, fixture.camera.attempts.size());

        FakeCamera.Attempt cameraAttempt = fixture.camera.latest();
        cameraAttempt.ready();
        assertEquals(FaceCaptureSession.State.PREPARING, fixture.controller.state());
        cameraAttempt.frame(fixture.frame(1L));
        assertEquals(FaceCaptureSession.State.DETECTING, fixture.controller.state());
        assertEquals(1, fixture.runtime.analyses.size());

        fixture.runtime.latestAnalysis().complete(goodAnalysis());
        fixture.time.advance(100L);
        cameraAttempt.frame(fixture.frame(2L));
        fixture.runtime.latestAnalysis().complete(goodAnalysis());
        fixture.time.advance(100L);
        cameraAttempt.frame(fixture.frame(3L));
        fixture.runtime.latestAnalysis().complete(goodAnalysis());

        assertEquals(FaceCaptureSession.State.CAPTURING, fixture.controller.state());
        assertEquals(1, fixture.encoder.calls.size());
        byte[] originalJpeg = jpeg();
        fixture.encoder.latest().succeed(originalJpeg);

        assertEquals(FaceCaptureSession.State.VERIFYING, fixture.controller.state());
        assertEquals(1, fixture.verifier.calls.size());
        assertTrue("original capture must be zero before verifier completion",
                allZero(originalJpeg));
        FakeVerifier.Call verification = fixture.verifier.latest();
        assertEquals("face-" + sessionId + "-3", verification.requestId);
        assertEquals(DEVICE, verification.deviceBinding);
        assertEquals(PROCESS, verification.processBinding);
        assertArrayEquals(jpeg(), verification.requestJpeg);

        fixture.bindings.device = "device-binding-later";
        fixture.bindings.process = "process-binding-later";
        String resultRequestId = new String(verification.requestId);
        FaceVerificationResult result = passed(
                resultRequestId,
                "opaque-customer-credential",
                "result-device-binding",
                "result-process-binding");
        verification.complete(result);

        assertEquals(FaceCaptureSession.State.SUCCESS, fixture.controller.state());
        assertEquals(1, fixture.camera.stopCalls);
        assertEquals(1, fixture.listener.terminals.size());
        TerminalOutcome terminal = fixture.listener.terminals.get(0).outcome;
        assertTrue(terminal.isSuccess());
        assertNull(terminal.failureReason());
        Success success = terminal.success();
        assertNotNull(success);
        assertSame(result, success.result());
        assertEquals(verification.requestId, success.expectedRequestId());
        assertNotSame("expected request must not be read back from result",
                result.requestId(), success.expectedRequestId());
        assertEquals(DEVICE, success.expectedDeviceBinding());
        assertEquals(PROCESS, success.expectedProcessBinding());
        assertFalse(success.expectedDeviceBinding().equals(result.deviceBinding()));
        assertFalse(success.expectedProcessBinding().equals(result.processBinding()));

        assertBefore(fixture.events, "license.check", "runtime.initialize");
        assertBefore(fixture.events, "runtime.initialize", "permission.request");
        assertBefore(fixture.events, "permission.request", "camera.start");
        assertBefore(fixture.events, "camera.start", "camera.ready");
        assertBefore(fixture.events, "camera.ready", "state.DETECTING");
        assertBefore(fixture.events, "state.CAPTURING", "encoder.encode");
        assertBefore(fixture.events, "state.VERIFYING", "verifier.verify");
        assertBefore(fixture.events, "camera.stop", "terminal.SUCCESS");

        FaceRecognitionController.QualityEvent quality = fixture.listener.quality.get(0);
        assertNotNull(quality.previewBox());
        assertEquals(640, quality.previewBox().frameWidth());
        assertEquals(480, quality.previewBox().frameHeight());
    }

    private void assertGrantedPermissionSkipsSystemRequest() {
        Fixture fixture = new Fixture();
        fixture.permission.granted = true;

        fixture.controller.start();
        assertEquals(0, fixture.camera.attempts.size());
        fixture.license.latest().ready();
        assertEquals(0, fixture.camera.attempts.size());
        fixture.runtime.latestInitialization().ready();

        assertEquals(0, fixture.permission.requests.size());
        assertEquals(1, fixture.camera.attempts.size());
        fixture.controller.cancel();
    }

    @Test
    public void preparationFailsClosedForBindingLicenseOrRuntimeAndHonorsGrantedPermission() {
        Fixture fixture = new Fixture();
        fixture.bindings.available = false;

        long sessionId = fixture.controller.start();

        assertFailure(fixture, sessionId, FailureReason.BINDING_UNAVAILABLE, false);
        assertEquals(0, fixture.license.attempts.size());
        assertEquals(0, fixture.runtime.initializations.size());
        assertEquals(0, fixture.permission.requests.size());
        assertEquals(0, fixture.camera.attempts.size());
        assertEquals(1, fixture.camera.stopCalls);
        assertInvalidLicenseStopsBeforeRuntime();
        assertRuntimeFailureStopsBeforePermission();
        assertGrantedPermissionSkipsSystemRequest();
    }

    private void assertInvalidLicenseStopsBeforeRuntime() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.controller.start();

        fixture.license.latest().fail(
                FaceRecognitionController.LicenseFailure.INVALID,
                "LICENSE_INVALID");

        assertFailure(fixture, sessionId, FailureReason.LICENSE_INVALID, false);
        assertEquals(0, fixture.runtime.initializations.size());
        assertEquals(0, fixture.camera.attempts.size());
    }

    @Test
    public void transientLicenseFailureStopsBeforeRuntimeButCanBeRetried() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.controller.start();

        fixture.license.latest().fail(
                FaceRecognitionController.LicenseFailure.UNAVAILABLE,
                "LICENSE_UNAVAILABLE");

        assertFailure(fixture, sessionId, FailureReason.LICENSE_FAILED, true);
        assertEquals(0, fixture.runtime.initializations.size());
        assertEquals(0, fixture.camera.attempts.size());
    }

    private void assertRuntimeFailureStopsBeforePermission() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.controller.start();
        fixture.license.latest().ready();

        fixture.runtime.latestInitialization().fail("MODEL_INIT_FAILED");

        assertFailure(fixture, sessionId, FailureReason.RUNTIME_FAILED, true);
        assertEquals(0, fixture.permission.requests.size());
        assertEquals(0, fixture.camera.attempts.size());
    }

    @Test
    public void permissionDenialIsOneShotAndLateGrantCannotStartCamera() {
        Fixture fixture = new Fixture();
        fixture.permission.granted = false;
        long sessionId = fixture.reachPermissionRequest();
        FakePermission.Request request = fixture.permission.latest();

        request.respond(false, false);
        request.respond(true, false);

        assertFailure(fixture, sessionId, FailureReason.PERMISSION_DENIED, true);
        assertEquals(1, fixture.permission.requests.size());
        assertEquals(0, fixture.camera.attempts.size());

        Fixture permanent = new Fixture();
        permanent.permission.granted = false;
        long permanentId = permanent.reachPermissionRequest();
        permanent.permission.latest().respond(false, true);
        assertFailure(permanent, permanentId,
                FailureReason.PERMISSION_PERMANENTLY_DENIED, false);
        assertEquals(1, permanent.permission.requests.size());
    }

    @Test
    public void cameraAnalysisJpegAndVerificationFailuresMapToTypedSafeOutcomes() {
        Fixture camera = new Fixture();
        long cameraId = camera.reachCameraStarted();
        camera.camera.latest().error("CAMERA_OPEN_FAILED");
        assertFailure(camera, cameraId, FailureReason.CAMERA_FAILED, true);

        Fixture stream = new Fixture();
        long streamId = stream.reachDetecting();
        stream.camera.latest().error("CAMERA_STREAM_FAILED");
        assertFailure(stream, streamId, FailureReason.CAMERA_FAILED, true);

        Fixture analysis = new Fixture();
        long analysisId = analysis.reachDetecting();
        analysis.runtime.latestAnalysis().fail("ANALYSIS_NATIVE_FAILED");
        assertFailure(analysis, analysisId, FailureReason.ANALYSIS_FAILED, true);

        Fixture encoder = new Fixture();
        long encoderId = encoder.reachCapturing();
        encoder.encoder.latest().fail("JPEG_ENCODER_FAILED");
        assertFailure(encoder, encoderId, FailureReason.JPEG_FAILED, true);

        Fixture verifier = new Fixture();
        long verifierId = verifier.reachVerifying();
        String requestId = verifier.verifier.latest().requestId;
        verifier.verifier.latest().complete(FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.NOT_PASSED, requestId));
        assertFailure(verifier, verifierId, FailureReason.VERIFICATION_FAILED, true);
    }

    @Test
    public void staleSameSessionAnalysisFailureCannotTerminateTheNewerFrame() {
        Fixture fixture = new Fixture();
        fixture.reachDetecting();
        FakeRuntime.AnalysisCall stale = fixture.runtime.latestAnalysis();
        stale.complete(noFaceAnalysis());
        fixture.time.advance(100L);
        fixture.camera.latest().frame(fixture.frame(2L));
        assertEquals(2, fixture.runtime.analyses.size());

        stale.fail("LATE_OLD_FRAME_FAILURE");

        assertEquals(FaceCaptureSession.State.DETECTING, fixture.controller.state());
        assertEquals(0, fixture.listener.terminals.size());
        fixture.runtime.latestAnalysis().complete(noFaceAnalysis());
        fixture.controller.cancel();
        assertEquals(FailureReason.CANCELLED,
                fixture.listener.terminals.get(0).outcome.failureReason());
    }

    @Test
    public void duplicateJpegFailureCannotPoisonVerificationTimeoutClassification() {
        Fixture fixture = new Fixture();
        fixture.reachCapturing();
        FakeEncoder.Call completed = fixture.encoder.latest();
        completed.succeed(jpeg());
        assertEquals(FaceCaptureSession.State.VERIFYING, fixture.controller.state());

        completed.fail("LATE_DUPLICATE_JPEG_FAILURE");
        completed.fail("SECOND_DUPLICATE_JPEG_FAILURE");
        fixture.time.advance(3_000L);

        assertFailure(fixture, fixture.listener.terminals.get(0).sessionId,
                FailureReason.VERIFICATION_TIMEOUT, true);
    }

    @Test
    public void verifierThrowNullAndCancellationClearEveryUntransferredRequestCopy()
            throws Exception {
        Fixture thrown = new Fixture();
        thrown.verifier.throwOnVerify = true;
        thrown.verifier.takeBeforeReject = true;
        long thrownSession = thrown.reachCapturing();
        thrown.encoder.latest().succeed(jpeg());
        assertFailure(thrown, thrownSession, FailureReason.VERIFICATION_FAILED, true);
        assertTrue(allZero(thrown.verifier.lastRequestBacking));
        assertTrue(allZero(thrown.verifier.rejectedOwnedJpeg));

        Fixture rejected = new Fixture();
        rejected.verifier.returnNull = true;
        rejected.verifier.takeBeforeReject = true;
        long rejectedSession = rejected.reachCapturing();
        rejected.encoder.latest().succeed(jpeg());
        assertFailure(rejected, rejectedSession, FailureReason.VERIFICATION_FAILED, true);
        assertTrue(allZero(rejected.verifier.lastRequestBacking));
        assertTrue(allZero(rejected.verifier.rejectedOwnedJpeg));

        Fixture cancelled = new Fixture();
        cancelled.verifier.takeOwnership = false;
        long cancelledSession = cancelled.reachVerifying();
        FakeVerifier.Call cancelledCall = cancelled.verifier.latest();
        cancelledCall.cancelEntered = new CountDownLatch(1);
        cancelledCall.allowCancel = new CountDownLatch(1);
        assertFalse(allZero(cancelled.verifier.lastRequestBacking));
        Thread cancellation = new Thread(cancelled.controller::cancel);
        cancellation.start();
        assertTrue(cancelledCall.cancelEntered.await(1L, TimeUnit.SECONDS));
        assertTrue("request cleanup cannot wait for a verifier cancellation handle",
                allZero(cancelled.verifier.lastRequestBacking));
        cancelledCall.allowCancel.countDown();
        cancellation.join(1_000L);
        assertFalse(cancellation.isAlive());
        assertFailure(cancelled, cancelledSession, FailureReason.CANCELLED, true);
        assertTrue(allZero(cancelled.verifier.lastRequestBacking));
        assertTrue(cancelledCall.cancelled);
    }

    @Test
    public void prepareTimeoutExpiresAtExactFifteenSecondBoundary() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.controller.start();

        fixture.time.advance(14_999L);
        assertEquals(0, fixture.listener.terminals.size());
        fixture.time.advance(1L);

        assertFailure(fixture, sessionId, FailureReason.PREPARE_TIMEOUT, true);
    }

    @Test
    public void cameraFirstFrameTimeoutExpiresAtExactFiveSecondBoundary() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachCameraStarted();
        fixture.camera.latest().ready();

        fixture.time.advance(4_999L);
        assertEquals(0, fixture.listener.terminals.size());
        fixture.time.advance(1L);

        assertFailure(fixture, sessionId, FailureReason.CAMERA_TIMEOUT, true);
    }

    @Test
    public void detectionTimeoutExpiresAtExactTenSecondBoundary() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachDetecting();

        fixture.time.advance(9_999L);
        assertEquals(0, fixture.listener.terminals.size());
        fixture.time.advance(1L);

        assertFailure(fixture, sessionId, FailureReason.DETECTION_TIMEOUT, true);
    }

    @Test
    public void jpegTimeoutExpiresAtExactThreeSecondBoundary() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachCapturing();

        fixture.time.advance(2_999L);
        assertEquals(0, fixture.listener.terminals.size());
        fixture.time.advance(1L);

        assertFailure(fixture, sessionId, FailureReason.JPEG_TIMEOUT, true);
    }

    @Test
    public void verificationTimeoutExpiresAtExactThreeSecondBoundary() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachVerifying();

        fixture.time.advance(2_999L);
        assertEquals(0, fixture.listener.terminals.size());
        fixture.time.advance(1L);

        assertFailure(fixture, sessionId, FailureReason.VERIFICATION_TIMEOUT, true);
        assertTrue(allZero(fixture.verifier.latest().ownedJpeg));
    }

    @Test
    public void onlineVerificationBudgetSurvivesLegacyDeadlineAndCompletes() {
        Fixture fixture = new Fixture(
                FaceRecognitionController.ONLINE_VERIFICATION_TIMEOUT_MILLIS);
        fixture.reachVerifying();
        FakeVerifier.Call verification = fixture.verifier.latest();

        fixture.time.advance(3_001L);
        assertEquals(FaceCaptureSession.State.VERIFYING, fixture.controller.state());
        assertEquals(0, fixture.listener.terminals.size());

        verification.complete(passed(verification.requestId, "opaque-online-handoff",
                verification.deviceBinding, verification.processBinding));
        assertTrue(fixture.listener.terminals.get(0).outcome.isSuccess());
    }

    @Test
    public void analysisIsSingleFlightAndOnlyLatestQueuedFrameRunsAtOneHundredMillis() {
        Fixture fixture = new Fixture();
        fixture.reachDetecting();
        FakeCamera.Attempt camera = fixture.camera.latest();
        camera.frame(fixture.frame(2L));
        camera.frame(fixture.frame(3L));

        assertEquals(1, fixture.runtime.analyses.size());
        assertEquals(1, fixture.releaseCount(2L));
        fixture.runtime.analyses.get(0).complete(noFaceAnalysis());
        fixture.time.advance(99L);
        assertEquals(1, fixture.runtime.analyses.size());
        fixture.time.advance(1L);

        assertEquals(2, fixture.runtime.analyses.size());
        assertEquals(3L, fixture.runtime.latestAnalysis().frameId);
        fixture.controller.cancel();
        assertEquals(1, fixture.releaseCount(1L));
        assertEquals(1, fixture.releaseCount(3L));
    }

    @Test
    public void synchronousPreparationCallbacksCancelLateHandlesAndStartCameraOnce() {
        Fixture fixture = new Fixture();
        fixture.permission.granted = true;
        fixture.license.synchronousReady = true;
        fixture.runtime.synchronousReady = true;

        long sessionId = fixture.controller.start();

        assertTrue(sessionId > 0L);
        assertEquals(1, fixture.license.attempts.size());
        assertEquals(1, fixture.runtime.initializations.size());
        assertEquals(1, fixture.license.latest().token.cancelCalls);
        assertEquals(1, fixture.runtime.latestInitialization().token.cancelCalls);
        assertEquals(1, fixture.camera.attempts.size());
        fixture.controller.cancel();
        assertEquals(1, fixture.camera.stopCalls);
    }

    @Test
    public void retryUsesNewSessionAndOldCallbacksCannotAffectTheNewAttempt() {
        Fixture fixture = new Fixture();
        long firstId = fixture.reachVerifying();
        FakeVerifier.Call staleVerification = fixture.verifier.latest();
        FakeCamera.Attempt staleCamera = fixture.camera.latest();

        fixture.controller.cancel();
        assertFailure(fixture, firstId, FailureReason.CANCELLED, true);
        long secondId = fixture.controller.retry();
        assertTrue(secondId > firstId);
        assertEquals(secondId, fixture.controller.activeSessionId());

        staleVerification.complete(passed(
                new String(staleVerification.requestId),
                "stale-credential", DEVICE, PROCESS));
        FaceFrame staleFrame = fixture.frame(99L);
        staleCamera.frame(staleFrame);

        assertEquals(FaceCaptureSession.State.PREPARING, fixture.controller.state());
        assertEquals(1, fixture.listener.terminals.size());
        assertEquals(1, fixture.releaseCount(99L));

        FakeLicense.Attempt secondLicense = fixture.license.latest();
        secondLicense.ready();
        fixture.runtime.latestInitialization().ready();
        assertEquals(2, fixture.camera.attempts.size());
        fixture.controller.cancel();
    }

    @Test
    public void cancelAndIdempotentCloseDrainOwnedWorkAndRejectFurtherStarts() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachDetecting();
        FakeRuntime.AnalysisCall analysis = fixture.runtime.latestAnalysis();

        fixture.controller.cancel();

        assertFailure(fixture, sessionId, FailureReason.CANCELLED, true);
        assertEquals(1, analysis.token.cancelCalls);
        assertEquals(1, fixture.releaseCount(1L));
        assertEquals(1, fixture.camera.stopCalls);

        fixture.controller.close();
        fixture.controller.close();
        assertEquals(1, fixture.encoder.closeCalls);
        assertEquals(1, fixture.camera.closeCalls);
        assertEquals(0L, fixture.controller.start());
        assertEquals(0L, fixture.controller.retry());
        assertBusyStartCancelsBeforeRetry();
    }

    private void assertBusyStartCancelsBeforeRetry() {
        Fixture fixture = new Fixture();
        long firstId = fixture.controller.start();

        assertEquals(0L, fixture.controller.start());
        long secondId = fixture.controller.retry();

        assertTrue(secondId > firstId);
        assertEquals(2, fixture.license.attempts.size());
        assertEquals(1, fixture.camera.stopCalls);
        fixture.controller.cancel();
    }

    @Test
    public void terminalValuesAreImmutableAndDiagnosticsCannotExposeUntrustedText() {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachCameraStarted();

        fixture.camera.latest().error("credential=secret/photo=/private/customer.jpg");

        assertFailure(fixture, sessionId, FailureReason.CAMERA_FAILED, true);
        TerminalOutcome outcome = fixture.listener.terminals.get(0).outcome;
        assertFalse(outcome.safeMessage().contains("secret"));
        assertFalse(outcome.safeMessage().contains("customer.jpg"));
        for (FakeDiagnostics.Entry entry : fixture.diagnostics.entries) {
            assertTrue(entry.code, entry.code.matches("[A-Z0-9_]{1,64}"));
            assertFalse(entry.code.contains("secret"));
        }
        assertImmutableValue(TerminalOutcome.class);
        assertImmutableValue(Success.class);
    }

    private static void assertFailure(Fixture fixture, long sessionId,
            FailureReason reason, boolean retryable) {
        assertEquals(1, fixture.listener.terminals.size());
        RecordingListener.Terminal event = fixture.listener.terminals.get(0);
        assertEquals(sessionId, event.sessionId);
        assertFalse(event.outcome.isSuccess());
        assertNull(event.outcome.success());
        assertEquals(reason, event.outcome.failureReason());
        assertEquals(retryable, event.outcome.retryable());
        assertNotNull(event.outcome.safeMessage());
    }

    private static void assertBefore(List<String> events, String first, String second) {
        int firstIndex = events.indexOf(first);
        int secondIndex = events.indexOf(second);
        assertTrue(first + " absent from " + events, firstIndex >= 0);
        assertTrue(second + " absent from " + events, secondIndex >= 0);
        assertTrue(first + " must precede " + second + " in " + events,
                firstIndex < secondIndex);
    }

    private static void assertImmutableValue(Class<?> type) {
        assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(field.getName(), Modifier.isPrivate(field.getModifiers()));
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
            assertFalse(field.getName(), field.getType().isArray());
            assertFalse(field.getName(), Throwable.class.isAssignableFrom(field.getType()));
        }
    }

    private static Analysis goodAnalysis() {
        return new Analysis(goodObservation(),
                new PreviewBox(640, 480, 320f, 240f, 120f, 140f));
    }

    private static Analysis noFaceAnalysis() {
        return new Analysis(noFaceObservation(), null);
    }

    private static FaceObservation goodObservation() {
        return new FaceObservation(
                640, 480, 1,
                0.95f, 320f, 240f, 120f, 140f,
                0f, 0f, 0f,
                0.1f, 0.9f,
                0.1f, 0.1f, 0.1f, 0.1f,
                0.1f, 0.1f, 0.1f,
                1f, 0.9f);
    }

    private static FaceObservation noFaceObservation() {
        return new FaceObservation(
                640, 480, 0,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 0f);
    }

    private static byte[] jpeg() {
        return new byte[] {
                (byte) 0xff, (byte) 0xd8, 0x11, 0x22, (byte) 0xff, (byte) 0xd9
        };
    }

    private static FaceVerificationResult passed(String requestId, String credential,
            String deviceBinding, String processBinding) {
        try {
            Method passed = FaceVerificationResult.class.getDeclaredMethod(
                    "passed",
                    FaceVerificationSource.class,
                    String.class,
                    String.class,
                    String.class,
                    long.class,
                    String.class,
                    String.class,
                    long.class);
            passed.setAccessible(true);
            return (FaceVerificationResult) passed.invoke(
                    null,
                    FaceVerificationSource.LOCAL_DEMO,
                    requestId,
                    credential,
                    "00112233445566778899aabbccddeeff",
                    60_000L,
                    deviceBinding,
                    processBinding,
                    1L);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] takeOwnedRequestJpeg(FaceVerificationRequest request) {
        try {
            Method take = FaceVerificationRequest.class.getDeclaredMethod("takeOwnedJpeg");
            take.setAccessible(true);
            return (byte[]) take.invoke(request);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] requestBacking(FaceVerificationRequest request) {
        try {
            Field field = FaceVerificationRequest.class.getDeclaredField("jpeg");
            field.setAccessible(true);
            return (byte[]) field.get(request);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean allZero(byte[] bytes) {
        if (bytes == null) return true;
        for (byte value : bytes) {
            if (value != 0) return false;
        }
        return true;
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<String>();
        final ManualTime time = new ManualTime();
        final FakeLicense license = new FakeLicense(events);
        final FakeRuntime runtime = new FakeRuntime(events);
        final FakePermission permission = new FakePermission(events);
        final FakeCamera camera = new FakeCamera(events);
        final FakeEncoder encoder = new FakeEncoder(events);
        final FakeVerifier verifier = new FakeVerifier(events);
        final FakeBindings bindings = new FakeBindings();
        final FakeDiagnostics diagnostics = new FakeDiagnostics();
        final RecordingListener listener = new RecordingListener(events);
        final Map<Long, Integer> releases = new HashMap<Long, Integer>();
        final FaceRecognitionController controller;

        Fixture() {
            this(FaceCaptureSession.DEFAULT_VERIFICATION_TIMEOUT_MILLIS);
        }

        Fixture(long verificationTimeoutMillis) {
            controller = new FaceRecognitionController(
                    time,
                    time,
                    time,
                    license,
                    runtime,
                    permission,
                    camera,
                    encoder,
                    verifier,
                    bindings,
                    new FaceFrameQualityGate(),
                    diagnostics,
                    listener,
                    verificationTimeoutMillis);
        }

        long reachPermissionRequest() {
            long sessionId = controller.start();
            license.latest().ready();
            runtime.latestInitialization().ready();
            assertEquals(1, permission.requests.size());
            return sessionId;
        }

        long reachCameraStarted() {
            permission.granted = true;
            long sessionId = controller.start();
            license.latest().ready();
            runtime.latestInitialization().ready();
            assertEquals(1, camera.attempts.size());
            return sessionId;
        }

        long reachDetecting() {
            long sessionId = reachCameraStarted();
            FakeCamera.Attempt attempt = camera.latest();
            attempt.ready();
            attempt.frame(frame(1L));
            assertEquals(FaceCaptureSession.State.DETECTING, controller.state());
            assertEquals(1, runtime.analyses.size());
            return sessionId;
        }

        long reachCapturing() {
            long sessionId = reachDetecting();
            runtime.latestAnalysis().complete(goodAnalysis());
            time.advance(100L);
            camera.latest().frame(frame(2L));
            runtime.latestAnalysis().complete(goodAnalysis());
            time.advance(100L);
            camera.latest().frame(frame(3L));
            runtime.latestAnalysis().complete(goodAnalysis());
            assertEquals(FaceCaptureSession.State.CAPTURING, controller.state());
            assertEquals(1, encoder.calls.size());
            return sessionId;
        }

        long reachVerifying() {
            long sessionId = reachCapturing();
            encoder.latest().succeed(jpeg());
            assertEquals(FaceCaptureSession.State.VERIFYING, controller.state());
            assertEquals(1, verifier.calls.size());
            return sessionId;
        }

        FaceFrame frame(long frameId) {
            byte[] bytes = new byte[24];
            Arrays.fill(bytes, (byte) 0x5a);
            return new FaceFrame(frameId, bytes, 4, 4,
                    0, 0, 0, false,
                    ignored -> releases.put(frameId, releaseCount(frameId) + 1));
        }

        int releaseCount(long frameId) {
            Integer count = releases.get(frameId);
            return count == null ? 0 : count;
        }
    }

    private static final class ManualTime implements FaceCaptureSession.Clock,
            FaceCaptureSession.Scheduler, FaceRecognitionController.EpochClock {
        long elapsed;
        long epoch = 10_000L;
        long nextOrder;
        final List<Scheduled> scheduled = new ArrayList<Scheduled>();

        @Override public long elapsedMillis() {
            return elapsed;
        }

        @Override public long epochMillis() {
            return epoch + elapsed;
        }

        @Override public FaceCaptureSession.Cancellable schedule(
                Runnable task, long delayMillis) {
            if (task == null || delayMillis < 0L) return null;
            Scheduled item = new Scheduled(
                    elapsed + delayMillis, ++nextOrder, task);
            scheduled.add(item);
            return item;
        }

        void advance(long millis) {
            if (millis < 0L) throw new IllegalArgumentException("negative advance");
            elapsed += millis;
            while (true) {
                Scheduled next = null;
                for (Scheduled item : scheduled) {
                    if (item.cancelled || item.ran || item.due > elapsed) continue;
                    if (next == null || item.due < next.due
                            || (item.due == next.due && item.order < next.order)) {
                        next = item;
                    }
                }
                if (next == null) return;
                next.ran = true;
                next.task.run();
            }
        }
    }

    private static final class Scheduled implements FaceCaptureSession.Cancellable {
        final long due;
        final long order;
        final Runnable task;
        boolean cancelled;
        boolean ran;

        Scheduled(long due, long order, Runnable task) {
            this.due = due;
            this.order = order;
            this.task = task;
        }

        @Override public void cancel() {
            cancelled = true;
        }
    }

    private static final class Token implements FaceCaptureSession.Cancellable {
        int cancelCalls;
        boolean cancelled;
        Runnable onFirstCancel;

        Token() {
        }

        Token(Runnable onFirstCancel) {
            this.onFirstCancel = onFirstCancel;
        }

        @Override public void cancel() {
            cancelCalls++;
            if (cancelled) return;
            cancelled = true;
            if (onFirstCancel != null) onFirstCancel.run();
        }
    }

    private static final class FakeLicense
            implements FaceRecognitionController.LicensePort {
        final List<String> events;
        final List<Attempt> attempts = new ArrayList<Attempt>();
        boolean synchronousReady;

        FakeLicense(List<String> events) {
            this.events = events;
        }

        @Override public FaceCaptureSession.Cancellable checkLocal(
                long sessionId, Callback callback) {
            events.add("license.check");
            Attempt attempt = new Attempt(sessionId, callback);
            attempts.add(attempt);
            if (synchronousReady) attempt.ready();
            return attempt.token;
        }

        Attempt latest() {
            return attempts.get(attempts.size() - 1);
        }

        static final class Attempt {
            final long sessionId;
            final Callback callback;
            final Token token = new Token();

            Attempt(long sessionId, Callback callback) {
                this.sessionId = sessionId;
                this.callback = callback;
            }

            void ready() {
                callback.onReady(sessionId);
            }

            void fail(String code) {
                callback.onFailure(sessionId, code);
            }

            void fail(FaceRecognitionController.LicenseFailure failure, String code) {
                callback.onFailure(sessionId, failure, code);
            }
        }
    }

    private static final class FakeRuntime
            implements FaceRecognitionController.RuntimePort {
        final List<String> events;
        final List<Initialization> initializations = new ArrayList<Initialization>();
        final List<AnalysisCall> analyses = new ArrayList<AnalysisCall>();
        boolean synchronousReady;

        FakeRuntime(List<String> events) {
            this.events = events;
        }

        @Override public FaceCaptureSession.Cancellable initialize(
                long sessionId, InitCallback callback) {
            events.add("runtime.initialize");
            Initialization initialization = new Initialization(sessionId, callback);
            initializations.add(initialization);
            if (synchronousReady) initialization.ready();
            return initialization.token;
        }

        @Override public FaceCaptureSession.Cancellable analyze(
                long sessionId,
                long frameId,
                FaceFrame.Borrow ownedFrame,
                AnalysisCallback callback) {
            events.add("runtime.analyze");
            AnalysisCall call = new AnalysisCall(
                    sessionId, frameId, ownedFrame, callback);
            analyses.add(call);
            return call.token;
        }

        Initialization latestInitialization() {
            return initializations.get(initializations.size() - 1);
        }

        AnalysisCall latestAnalysis() {
            return analyses.get(analyses.size() - 1);
        }

        static final class Initialization {
            final long sessionId;
            final InitCallback callback;
            final Token token = new Token();

            Initialization(long sessionId, InitCallback callback) {
                this.sessionId = sessionId;
                this.callback = callback;
            }

            void ready() {
                callback.onReady(sessionId);
            }

            void fail(String code) {
                callback.onFailure(sessionId, code);
            }
        }

        static final class AnalysisCall {
            final long sessionId;
            final long frameId;
            final AnalysisCallback callback;
            final Token token;
            FaceFrame.Borrow borrow;

            AnalysisCall(long sessionId, long frameId,
                    FaceFrame.Borrow borrow, AnalysisCallback callback) {
                this.sessionId = sessionId;
                this.frameId = frameId;
                this.borrow = borrow;
                this.callback = callback;
                this.token = new Token(this::closeBorrow);
            }

            void complete(Analysis result) {
                closeBorrow();
                callback.onCompleted(sessionId, frameId, result);
            }

            void fail(String code) {
                closeBorrow();
                callback.onFailure(sessionId, frameId, code);
            }

            private void closeBorrow() {
                FaceFrame.Borrow owned = borrow;
                borrow = null;
                FaceRecognitionControllerTest.close(owned);
            }
        }
    }

    private static final class FakePermission
            implements FaceRecognitionController.PermissionPort {
        final List<String> events;
        final List<Request> requests = new ArrayList<Request>();
        boolean granted;

        FakePermission(List<String> events) {
            this.events = events;
        }

        @Override public boolean isGranted() {
            return granted;
        }

        @Override public FaceCaptureSession.Cancellable requestOnce(
                long sessionId, Callback callback) {
            events.add("permission.request");
            Request request = new Request(sessionId, callback);
            requests.add(request);
            return request.token;
        }

        Request latest() {
            return requests.get(requests.size() - 1);
        }

        static final class Request {
            final long sessionId;
            final Callback callback;
            final Token token = new Token();

            Request(long sessionId, Callback callback) {
                this.sessionId = sessionId;
                this.callback = callback;
            }

            void respond(boolean granted, boolean permanentlyDenied) {
                callback.onResult(sessionId, granted, permanentlyDenied);
            }
        }
    }

    private static final class FakeCamera
            implements FaceRecognitionController.CameraPort {
        final List<String> events;
        final List<Attempt> attempts = new ArrayList<Attempt>();
        int stopCalls;
        int closeCalls;
        boolean closed;

        FakeCamera(List<String> events) {
            this.events = events;
        }

        @Override public FaceCaptureSession.Cancellable start(
                long sessionId, Callback callback) {
            events.add("camera.start");
            Attempt attempt = new Attempt(events, sessionId, callback);
            attempts.add(attempt);
            return attempt.token;
        }

        @Override public void stop(long sessionId) {
            events.add("camera.stop");
            stopCalls++;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            closeCalls++;
        }

        Attempt latest() {
            return attempts.get(attempts.size() - 1);
        }

        static final class Attempt {
            final List<String> events;
            final long sessionId;
            final Callback callback;
            final Token token = new Token();

            Attempt(List<String> events, long sessionId, Callback callback) {
                this.events = events;
                this.sessionId = sessionId;
                this.callback = callback;
            }

            void ready() {
                events.add("camera.ready");
                callback.onReady(sessionId);
            }

            void frame(FaceFrame frame) {
                callback.onFrame(sessionId, frame);
            }

            void error(String code) {
                callback.onError(sessionId, code);
            }
        }
    }

    private static final class FakeEncoder implements FaceJpegEncoder {
        final List<String> events;
        final List<Call> calls = new ArrayList<Call>();
        int closeCalls;
        boolean closed;

        FakeEncoder(List<String> events) {
            this.events = events;
        }

        @Override public FaceCaptureSession.Cancellable encode(
                long frameId, FaceFrame.Borrow ownedFrame, Callback callback) {
            events.add("encoder.encode");
            Call call = new Call(frameId, ownedFrame, callback);
            calls.add(call);
            return call.token;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            closeCalls++;
            for (Call call : calls) call.token.cancel();
        }

        Call latest() {
            return calls.get(calls.size() - 1);
        }

        static final class Call {
            final long frameId;
            final Callback callback;
            final Token token;
            FaceFrame.Borrow borrow;

            Call(long frameId, FaceFrame.Borrow borrow, Callback callback) {
                this.frameId = frameId;
                this.borrow = borrow;
                this.callback = callback;
                this.token = new Token(this::closeBorrow);
            }

            void succeed(byte[] ownedBytes) {
                closeBorrow();
                FaceJpegEncoder.OwnedJpeg owned =
                        new FaceJpegEncoder.OwnedJpeg(ownedBytes);
                try {
                    callback.onEncoded(frameId, owned);
                } finally {
                    owned.close();
                }
            }

            void fail(String code) {
                closeBorrow();
                callback.onFailure(
                        frameId, "人脸照片处理失败，请重新尝试", code);
            }

            private void closeBorrow() {
                FaceFrame.Borrow owned = borrow;
                borrow = null;
                FaceRecognitionControllerTest.close(owned);
            }
        }
    }

    private static final class FakeVerifier implements FaceVerificationClient {
        final List<String> events;
        final List<Call> calls = new ArrayList<Call>();
        boolean throwOnVerify;
        boolean returnNull;
        boolean takeBeforeReject;
        boolean takeOwnership = true;
        byte[] lastRequestBacking;
        byte[] rejectedOwnedJpeg;

        FakeVerifier(List<String> events) {
            this.events = events;
        }

        @Override public Cancellable verify(
                FaceVerificationRequest request, Callback callback) {
            events.add("verifier.verify");
            lastRequestBacking = requestBacking(request);
            if (takeBeforeReject) {
                rejectedOwnedJpeg = takeOwnedRequestJpeg(request);
            }
            if (throwOnVerify) throw new IllegalStateException("test verifier rejection");
            if (returnNull) return null;
            Call call = new Call(request, callback, takeOwnership);
            calls.add(call);
            return call;
        }

        Call latest() {
            return calls.get(calls.size() - 1);
        }

        static final class Call implements Cancellable {
            final Callback callback;
            final String requestId;
            final String deviceBinding;
            final String processBinding;
            final byte[] requestJpeg;
            byte[] ownedJpeg;
            boolean cancelled;
            CountDownLatch cancelEntered;
            CountDownLatch allowCancel;

            Call(FaceVerificationRequest request, Callback callback,
                    boolean takeOwnership) {
                this.callback = callback;
                this.requestId = request.requestId();
                this.deviceBinding = request.deviceBinding();
                this.processBinding = request.processBinding();
                this.ownedJpeg = takeOwnership ? takeOwnedRequestJpeg(request) : null;
                this.requestJpeg = ownedJpeg == null
                        ? new byte[0] : ownedJpeg.clone();
            }

            void complete(FaceVerificationResult result) {
                zeroOwned();
                callback.onCompleted(result);
            }

            @Override public void cancel() {
                cancelled = true;
                if (cancelEntered != null) cancelEntered.countDown();
                if (allowCancel != null) {
                    try {
                        if (!allowCancel.await(5L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test cancel release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test cancel interrupted", interrupted);
                    }
                }
                zeroOwned();
            }

            private void zeroOwned() {
                if (ownedJpeg != null) Arrays.fill(ownedJpeg, (byte) 0);
            }
        }
    }

    private static final class FakeBindings
            implements FaceRecognitionController.BindingPort {
        boolean available = true;
        String device = DEVICE;
        String process = PROCESS;

        @Override public boolean isAvailable() {
            return available;
        }

        @Override public String deviceBinding() {
            return device;
        }

        @Override public String processBinding() {
            return process;
        }
    }

    private static final class FakeDiagnostics
            implements FaceRecognitionController.DiagnosticSink {
        static final class Entry {
            final long sessionId;
            final DiagnosticStage stage;
            final String code;

            Entry(long sessionId, DiagnosticStage stage, String code) {
                this.sessionId = sessionId;
                this.stage = stage;
                this.code = code;
            }
        }

        final List<Entry> entries = new ArrayList<Entry>();

        @Override public void record(
                long sessionId, DiagnosticStage stage, String boundedCode) {
            entries.add(new Entry(sessionId, stage, boundedCode));
        }
    }

    private static final class RecordingListener
            implements FaceRecognitionController.Listener {
        static final class Terminal {
            final long sessionId;
            final TerminalOutcome outcome;

            Terminal(long sessionId, TerminalOutcome outcome) {
                this.sessionId = sessionId;
                this.outcome = outcome;
            }
        }

        final List<String> events;
        final List<FaceRecognitionController.QualityEvent> quality =
                new ArrayList<FaceRecognitionController.QualityEvent>();
        final List<Terminal> terminals = new ArrayList<Terminal>();

        RecordingListener(List<String> events) {
            this.events = events;
        }

        @Override public void onStateChanged(
                long sessionId, FaceCaptureSession.State state) {
            events.add("state." + state.name());
        }

        @Override public void onQualityDecision(
                long sessionId, FaceRecognitionController.QualityEvent event) {
            quality.add(event);
        }

        @Override public void onTerminal(
                long sessionId, TerminalOutcome outcome) {
            events.add("terminal."
                    + (outcome.isSuccess()
                    ? "SUCCESS" : outcome.failureReason().name()));
            terminals.add(new Terminal(sessionId, outcome));
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
