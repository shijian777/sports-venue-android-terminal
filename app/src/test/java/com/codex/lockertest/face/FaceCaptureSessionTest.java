package com.codex.lockertest.face;

import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationSource;
import com.codex.lockertest.face.verification.FaceVerificationStatus;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class FaceCaptureSessionTest {
    @Test
    public void stateAndActiveIdHaveExactInitialActiveAndTerminalSemantics() {
        Fixture f = new Fixture();
        assertNull(f.session.state());
        assertEquals(0L, f.session.activeSessionId());
        long first = f.session.start();
        assertTrue(first > 0L);
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        assertEquals(first, f.session.activeSessionId());
        assertEquals(0L, f.session.start());
        assertEquals(1, f.actions.prepareCalls);
        f.session.cancel();
        assertEquals(FaceCaptureSession.State.CANCELLED, f.session.state());
        assertEquals(0L, f.session.activeSessionId());
        long second = f.session.start();
        assertTrue(second > first);
        assertEquals(second, f.session.activeSessionId());
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        f.session.cancel();
        assertEquals(2, f.actions.stopCalls);
    }

    @Test
    public void runtimeTimeoutExpiresExactlyAtFifteenSeconds() {
        Fixture f = new Fixture();
        f.session.start();
        f.advance(14_999);
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        f.advance(1);
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void runtimeDeadlineIsArmedBeforeQualityGateResetCanBlock() throws Exception {
        Fixture f = new Fixture();
        AtomicLong sid = new AtomicLong();
        Thread starter;
        f.scheduler.scheduleCompleted = new CountDownLatch(1);
        synchronized (f.qualityGate) {
            starter = new Thread(() -> sid.set(f.session.start()));
            starter.start();
            awaitBooleanField(f.session, "starting", true);
            assertTrue(f.scheduler.scheduleCompleted.await(1, TimeUnit.SECONDS));
            f.advance(15_000L);
            assertEquals(FaceCaptureSession.State.TIMEOUT, f.session.state());
            assertEquals(0, f.listener.terminalCount.get());
        }
        starter.join(1000);
        assertFalse(starter.isAlive());
        assertTrue(sid.get() > 0L);
        assertEquals(0, f.actions.prepareCalls);
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void cameraTimerSurvivesReadyAndEndsOnFirstAcceptedFrame() {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        f.advance(4_999);
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        FaceFrame first = f.frame(1);
        f.session.onPreviewFrame(sid, first);
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        assertEquals(1, f.actions.analyzeCalls);
        f.advance(1);
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.session.cancel();

        Fixture timeout = new Fixture();
        sid = timeout.session.start();
        timeout.session.onRuntimeReady(sid);
        timeout.session.onCameraReady(sid);
        timeout.advance(5_000);
        assertTerminal(timeout, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void cameraReadyReportsAcceptanceAndRejectsDuplicateTimeoutAndSurfaceFailure() {
        Fixture accepted = new Fixture();
        long acceptedId = accepted.session.start();
        accepted.session.onRuntimeReady(acceptedId);
        assertTrue(accepted.session.onCameraReady(acceptedId));
        assertFalse(accepted.session.onCameraReady(acceptedId));
        accepted.session.cancel();
        assertFalse(accepted.session.onCameraReady(acceptedId));

        Fixture timeout = new Fixture();
        long timeoutId = timeout.session.start();
        timeout.session.onRuntimeReady(timeoutId);
        timeout.advance(5_000L);
        assertTerminal(timeout, FaceCaptureSession.State.TIMEOUT);
        assertFalse(timeout.session.onCameraReady(timeoutId));

        Fixture surfaceDestroyed = new Fixture();
        long destroyedId = surfaceDestroyed.session.start();
        surfaceDestroyed.session.onRuntimeReady(destroyedId);
        surfaceDestroyed.session.onFailure(
                destroyedId, FaceCaptureSession.FailureStage.CAMERA);
        assertTerminal(surfaceDestroyed, FaceCaptureSession.State.FAILURE);
        assertFalse(surfaceDestroyed.session.onCameraReady(destroyedId));
    }

    @Test
    public void detectionEncodeAndVerificationTimersExpireAtExactBoundaries() {
        Fixture detection = new Fixture();
        detection.reachDetecting();
        detection.advance(9_999);
        assertEquals(FaceCaptureSession.State.DETECTING, detection.session.state());
        detection.advance(1);
        assertTerminal(detection, FaceCaptureSession.State.TIMEOUT);

        Fixture encode = new Fixture();
        encode.reachCapturing();
        encode.advance(2_999);
        assertEquals(FaceCaptureSession.State.CAPTURING, encode.session.state());
        encode.advance(1);
        assertTerminal(encode, FaceCaptureSession.State.TIMEOUT);

        Fixture verify = new Fixture();
        verify.reachVerifying();
        verify.advance(2_999);
        assertEquals(FaceCaptureSession.State.VERIFYING, verify.session.state());
        verify.advance(1);
        assertTerminal(verify, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void configuredOnlineVerificationBudgetAllowsSuccessAfterLegacyDeadline() {
        Fixture online = new Fixture(35_000L);
        long sid = online.reachVerifying();

        online.advance(3_001L);
        assertEquals(FaceCaptureSession.State.VERIFYING, online.session.state());

        online.actions.completeVerification(passed(online.actions.verifyRequestId));
        assertTerminal(online, FaceCaptureSession.State.SUCCESS);
    }

    @Test
    public void configuredOnlineVerificationBudgetTimesOutAndCancelsAtItsBoundary() {
        Fixture online = new Fixture(35_000L);
        online.reachVerifying();

        online.advance(34_999L);
        assertEquals(FaceCaptureSession.State.VERIFYING, online.session.state());
        assertEquals(0, online.actions.verifyToken.cancelCount.get());

        online.advance(1L);
        assertTerminal(online, FaceCaptureSession.State.TIMEOUT);
        assertEquals(1, online.actions.verifyToken.cancelCount.get());
    }

    @Test
    public void cameraDeadlineIsArmedBeforeDetachedRuntimeCleanupCanBlock() throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.actions.prepareToken.cancelEntered = new CountDownLatch(1);
        f.actions.prepareToken.allowCancel = new CountDownLatch(1);

        Thread ready = new Thread(() -> f.session.onRuntimeReady(sid));
        ready.start();
        assertTrue(f.actions.prepareToken.cancelEntered.await(1, TimeUnit.SECONDS));
        f.advance(5_000L);
        assertEquals(FaceCaptureSession.State.TIMEOUT, f.session.state());
        assertEquals(0, f.listener.terminalCount.get());

        f.actions.prepareToken.allowCancel.countDown();
        ready.join(1000);
        assertFalse(ready.isAlive());
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void encodeDeadlineIsArmedBeforeDetachedDetectionCleanupCanBlock() throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        Token detectionTimer = f.scheduler.activeTokenAt(10_000L);
        f.actions.completeObservation(validObservation());
        f.nextFrame(sid, 2, validObservation());
        f.advance(100L);
        f.session.onPreviewFrame(sid, f.frame(3));
        detectionTimer.cancelEntered = new CountDownLatch(1);
        detectionTimer.allowCancel = new CountDownLatch(1);

        Thread capture = new Thread(() -> f.actions.completeObservation(validObservation()));
        capture.start();
        assertTrue(detectionTimer.cancelEntered.await(1, TimeUnit.SECONDS));
        f.advance(3_000L);
        assertEquals(FaceCaptureSession.State.TIMEOUT, f.session.state());
        assertEquals(0, f.listener.terminalCount.get());

        detectionTimer.allowCancel.countDown();
        capture.join(1000);
        assertFalse(capture.isAlive());
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void verificationDeadlineIsArmedBeforeDetachedEncodeCleanupCanBlock()
            throws Exception {
        Fixture f = new Fixture();
        f.reachCapturing();
        f.actions.encodeToken.cancelEntered = new CountDownLatch(1);
        f.actions.encodeToken.allowCancel = new CountDownLatch(1);

        Thread jpeg = new Thread(() -> f.actions.completeJpeg(jpeg()));
        jpeg.start();
        assertTrue(f.actions.encodeToken.cancelEntered.await(1, TimeUnit.SECONDS));
        f.advance(3_000L);
        assertEquals(FaceCaptureSession.State.TIMEOUT, f.session.state());
        assertEquals(0, f.listener.terminalCount.get());

        f.actions.encodeToken.allowCancel.countDown();
        jpeg.join(1000);
        assertFalse(jpeg.isAlive());
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void analysisIsSingleFlightLatestOnlyAndPumpsAtOneHundredMillis() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        FaceFrame second = f.frame(2);
        FaceFrame third = f.frame(3);
        f.session.onPreviewFrame(sid, second);
        f.session.onPreviewFrame(sid, third);
        assertEquals(1, f.actions.analyzeCalls);
        assertEquals(1, f.releaseCount(2));
        f.actions.completeObservation(validObservation());
        f.advance(99);
        assertEquals(1, f.actions.analyzeCalls);
        f.advance(1);
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(3L, f.actions.analysisFrameId);
        f.session.cancel();
        assertEquals(1, f.releaseCount(1));
        assertEquals(1, f.releaseCount(3));
    }

    @Test
    public void directAnalysisAtRateBoundaryDiscardsOlderPendingMailboxFrame() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(2));
        f.actions.completeObservation(validObservation());

        f.clock.now = 100L;
        f.session.onPreviewFrame(sid, f.frame(3));
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(3L, f.actions.analysisFrameId);
        assertEquals(1, f.releaseCount(2));

        f.actions.completeObservation(noFaceObservation());
        f.clock.now = 200L;
        f.scheduler.runDue();
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(1, f.releaseCount(2));
        f.session.cancel();
    }

    @Test
    public void invalidatedPumpReservationCannotArmOrScheduleAStaleTimer() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        int schedulesBefore = f.scheduler.scheduleCalls;
        setBooleanField(f.session, "pumpTimerScheduling", true);
        setLongField(f.session, "pumpScheduleGeneration", 41L);

        setBooleanField(f.session, "pumpTimerScheduling", false);
        setLongField(f.session, "pumpScheduleGeneration", 42L);
        assertFalse(invokeArmPumpTimer(f.session, sid, 100L, 41L));
        assertEquals(schedulesBefore, f.scheduler.scheduleCalls);
        f.session.cancel();
    }

    @Test
    public void synchronousPumpCallbackRedrivesPendingFrameWithoutAnotherPreview() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(2));
        f.scheduler.runNextSynchronously = true;
        f.actions.completeObservation(noFaceObservation());
        assertEquals(1, f.actions.analyzeCalls);
        f.advance(100L);
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(2L, f.actions.analysisFrameId);
        f.session.cancel();
    }

    @Test
    public void cancellationDrainsAPumpScheduleReservationBeforeAnotherSessionCanStart()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(noFaceObservation());
        f.scheduler.scheduleEntered = new CountDownLatch(1);
        f.scheduler.allowSchedule = new CountDownLatch(1);

        Thread preview = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(2)));
        preview.start();
        assertTrue(f.scheduler.scheduleEntered.await(1, TimeUnit.SECONDS));
        f.session.cancel();

        assertEquals(0, f.listener.terminalCount.get());
        assertEquals(0L, f.session.start());
        f.scheduler.allowSchedule.countDown();
        preview.join(1000);
        assertFalse(preview.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertTrue(f.session.start() > sid);
        f.session.cancel();
    }

    @Test
    public void cancellationDrainsAPumpClaimBlockedInMailboxTakeBeforeRestart()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(2));
        f.actions.completeObservation(noFaceObservation());
        f.clock.now = 100L;
        Object mailbox = getObjectField(f.session, "mailbox");
        Thread due = new Thread(f.scheduler::runDue);

        synchronized (mailbox) {
            due.start();
            awaitThreadState(due, Thread.State.BLOCKED);
            awaitBooleanField(f.session, "pumpClaimed", true);
            f.session.cancel();
            assertEquals(0, f.listener.terminalCount.get());
            assertEquals(0L, f.session.start());
        }

        due.join(1000);
        assertFalse(due.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertTrue(f.session.start() > sid);
        f.session.cancel();
    }

    @Test
    public void detectionDeadlineIsArmedBeforeAConcurrentSecondFrameCanStartAnalysis()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        Token cameraTimer = f.scheduler.lastReturned;
        cameraTimer.cancelEntered = new CountDownLatch(1);
        cameraTimer.allowCancel = new CountDownLatch(1);

        Thread first = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(1)));
        first.start();
        assertTrue(cameraTimer.cancelEntered.await(1, TimeUnit.SECONDS));
        assertEquals(3, f.scheduler.scheduleCalls);
        assertEquals(10_000L, f.scheduler.firstActiveDueAfter(f.clock.now));
        f.session.onPreviewFrame(sid, f.frame(2));
        assertEquals(0, f.actions.analyzeCalls);

        cameraTimer.allowCancel.countDown();
        first.join(1000);
        assertFalse(first.isAlive());
        assertEquals(1, f.actions.analyzeCalls);
        assertEquals(2L, f.actions.analysisFrameId);
        assertEquals(1, f.releaseCount(1));
        f.session.cancel();
    }

    @Test
    public void duplicateAndLowerFrameIdsAreClosedAndNeverReplaceLatest() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(1));
        f.session.onPreviewFrame(sid, f.frame(3));
        f.session.onPreviewFrame(sid, f.frame(2));
        assertEquals(1, f.releaseCount(1));
        assertEquals(1, f.releaseCount(2));
        f.actions.completeObservation(validObservation());
        f.advance(100);
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(3L, f.actions.analysisFrameId);
        f.session.cancel();
    }

    @Test
    public void cancellationWaitsForAcceptedPreviewReplacementReleaseToFinish()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(2));
        f.blockReleaseKey = 2L;
        f.releaseEntered = new CountDownLatch(1);
        f.allowRelease = new CountDownLatch(1);

        Thread replace = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(3)));
        replace.start();
        assertTrue(f.releaseEntered.await(1, TimeUnit.SECONDS));
        f.session.cancel();
        assertEquals(0, f.listener.terminalCount.get());
        assertEquals(0L, f.session.start());

        f.allowRelease.countDown();
        replace.join(1000);
        assertFalse(replace.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertTrue(f.session.start() > sid);
        f.session.cancel();
    }

    @Test
    public void staleConcurrentPreviewClockReadDoesNotReportAFalseRollback()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(noFaceObservation());
        f.clock.now = 99L;
        CountDownLatch readCaptured = new CountDownLatch(1);
        CountDownLatch allowReadReturn = new CountDownLatch(1);
        f.clock.readCaptured = readCaptured;
        f.clock.allowReadReturn = allowReadReturn;

        Thread newer = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(3)));
        newer.start();
        assertTrue(readCaptured.await(1, TimeUnit.SECONDS));
        f.clock.now = 100L;
        f.session.onPreviewFrame(sid, f.frame(2));
        assertEquals(2L, f.actions.analysisFrameId);

        allowReadReturn.countDown();
        newer.join(1000);
        assertFalse(newer.isAlive());
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.actions.completeObservation(noFaceObservation());
        f.advance(100L);
        assertEquals(3L, f.actions.analysisFrameId);
        f.session.cancel();
    }

    @Test
    public void staleConcurrentPumpClockReadDoesNotReportAFalseRollback()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(noFaceObservation());
        f.clock.now = 99L;
        CountDownLatch readCaptured = new CountDownLatch(1);
        CountDownLatch allowReadReturn = new CountDownLatch(1);
        f.clock.readCaptured = readCaptured;
        f.clock.allowReadReturn = allowReadReturn;

        Thread oldPump = new Thread(() -> invokePump(f.session, sid));
        oldPump.start();
        assertTrue(readCaptured.await(1, TimeUnit.SECONDS));
        f.clock.now = 100L;
        f.actions.syncAnalysis = true;
        f.session.onPreviewFrame(sid, f.frame(2));
        assertEquals(1, f.actions.analyzeCalls);

        allowReadReturn.countDown();
        oldPump.join(1000);
        assertFalse(oldPump.isAlive());
        f.advance(1L);
        assertEquals(2L, f.actions.analysisFrameId);
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.session.cancel();
    }

    @Test
    public void clockRollbackBetweenEligibilityAndActualAnalysisStartFailsBeforeAction() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(noFaceObservation());
        f.clock.scriptedValues = new long[] {200L, 150L};
        f.clock.scriptedIndex.set(0);

        f.session.onPreviewFrame(sid, f.frame(2));

        assertEquals(1, f.actions.analyzeCalls);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void synchronousOldAnalysisNullOrThrowCannotFailANewerInFlightFrame()
            throws Exception {
        assertOldAnalysisCompletionCannotFailNewer(false);
        assertOldAnalysisCompletionCannotFailNewer(true);
    }

    @Test
    public void runtimeActionNullClaimPreventsCameraPhaseAdvance() throws Exception {
        BlockingLinearizationProbe probe = new BlockingLinearizationProbe(
                FaceCaptureSession.LinearizationProbe.Event.ACTION_FAILURE);
        Fixture f = new Fixture(probe);
        probe.session = f.session;
        f.actions.nullPrepare = true;
        AtomicLong returnedSid = new AtomicLong();
        Thread starter = new Thread(() -> returnedSid.set(f.session.start()));
        FaceCaptureSession.State observedState;
        long observedActive;
        int observedCameraCalls;

        starter.start();
        try {
            assertTrue(probe.entered.await(1, TimeUnit.SECONDS));
            long sid = f.actions.prepareSessionId;
            assertTrue(sid > 0L);
            f.session.onRuntimeReady(sid);
            observedState = f.session.state();
            observedActive = f.session.activeSessionId();
            observedCameraCalls = f.actions.cameraCalls;
        } finally {
            probe.allow.countDown();
            starter.join(1000);
        }

        assertFalse(starter.isAlive());
        assertFalse(probe.holdsLockViolation.get());
        assertEquals(FaceCaptureSession.State.FAILURE, observedState);
        assertEquals(0L, observedActive);
        assertEquals(0, observedCameraCalls);
        assertTrue(returnedSid.get() > 0L);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void analysisNullClaimPreventsNextFrameABA() throws Exception {
        BlockingLinearizationProbe probe = new BlockingLinearizationProbe(
                FaceCaptureSession.LinearizationProbe.Event.ACTION_FAILURE);
        Fixture f = new Fixture(probe);
        probe.session = f.session;
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        f.actions.nullAnalyze = true;
        Thread first = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(1)));
        FaceCaptureSession.State observedState;
        long observedActive;
        int observedAnalyzeCalls;

        first.start();
        try {
            assertTrue(probe.entered.await(1, TimeUnit.SECONDS));
            f.actions.nullAnalyze = false;
            f.session.onObservation(sid, 1L, noFaceObservation());
            f.clock.now = 100L;
            f.session.onPreviewFrame(sid, f.frame(2));
            observedState = f.session.state();
            observedActive = f.session.activeSessionId();
            observedAnalyzeCalls = f.actions.analyzeCalls;
        } finally {
            probe.allow.countDown();
            first.join(1000);
        }

        assertFalse(first.isAlive());
        assertFalse(probe.holdsLockViolation.get());
        assertEquals(FaceCaptureSession.State.FAILURE, observedState);
        assertEquals(0L, observedActive);
        assertEquals(1, observedAnalyzeCalls);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void verificationTimeoutClaimBeatsSuccessAtBoundary() throws Exception {
        BlockingLinearizationProbe probe = new BlockingLinearizationProbe(
                FaceCaptureSession.LinearizationProbe.Event.TIMER_EXPIRY);
        Fixture f = new Fixture(probe);
        probe.session = f.session;
        long sid = f.reachVerifying();
        FaceVerificationResult success = passed(f.actions.verifyRequestId);
        f.clock.now += 3_000L;
        Thread timeout = new Thread(f.scheduler::runDue);
        FaceCaptureSession.State observedState;
        long observedActive;
        int observedTerminalCount;

        timeout.start();
        try {
            assertTrue(probe.entered.await(1, TimeUnit.SECONDS));
            f.session.onVerification(sid, success);
            observedState = f.session.state();
            observedActive = f.session.activeSessionId();
            observedTerminalCount = f.listener.terminalCount.get();
        } finally {
            probe.allow.countDown();
            timeout.join(1000);
        }

        assertFalse(timeout.isAlive());
        assertFalse(probe.holdsLockViolation.get());
        assertEquals(FaceCaptureSession.State.TIMEOUT, observedState);
        assertEquals(0L, observedActive);
        assertEquals(0, observedTerminalCount);
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void clockRollbackFailsClosed() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(validObservation());
        f.clock.now = -1;
        f.session.onPreviewFrame(sid, f.frame(2));
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void rejectionBreaksStreakAndThirdNewQualifiedFrameCapturesExactFrame() {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.actions.completeObservation(validObservation());
        f.nextFrame(sid, 2, validObservation());
        f.nextFrame(sid, 3, noFaceObservation());
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.nextFrame(sid, 4, validObservation());
        f.nextFrame(sid, 5, validObservation());
        f.nextFrame(sid, 6, validObservation());
        assertEquals(FaceCaptureSession.State.CAPTURING, f.session.state());
        assertEquals(1, f.actions.encodeCalls);
        assertEquals(6L, f.actions.encodeFrameId);
        assertEquals(6, f.listener.decisions.size());
        f.session.cancel();
    }

    @Test
    public void jpegHandoffZerosOriginalBeforeVerificationAndSuccessTerminalCallback() {
        Fixture f = new Fixture();
        long sid = f.reachCapturing();
        byte[] jpeg = jpeg();
        f.listener.bytesToCheckAtTerminal = jpeg;
        f.actions.completeJpeg(jpeg);
        assertEquals(FaceCaptureSession.State.VERIFYING, f.session.state());
        assertArrayEquals(new byte[jpeg.length], jpeg);
        assertArrayEquals(jpegTemplate(), f.actions.copiedJpeg);
        assertEquals("face-" + sid + "-3", f.actions.verifyRequestId);
        f.actions.completeVerification(passed(f.actions.verifyRequestId));
        assertTerminal(f, FaceCaptureSession.State.SUCCESS);
        assertTrue(f.listener.zeroAtTerminal);
        assertEquals(1, f.actions.verifyCalls);
    }

    @Test
    public void staleAndInvalidJpegsAreZeroedAndMatchingInvalidFails() {
        Fixture f = new Fixture();
        long sid = f.reachCapturing();
        byte[] stale = jpeg();
        f.session.onJpegReady(sid + 1, 3, stale);
        assertArrayEquals(new byte[stale.length], stale);
        byte[] wrongFrame = jpeg();
        f.session.onJpegReady(sid, 2, wrongFrame);
        assertArrayEquals(new byte[wrongFrame.length], wrongFrame);
        byte[] invalid = new byte[] {1, 2, 3, 4};
        f.session.onJpegReady(sid, 3, invalid);
        assertArrayEquals(new byte[4], invalid);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void wrongVerificationRequestIsIgnoredUntilVerifierTimeout() {
        Fixture f = new Fixture();
        long sid = f.reachVerifying();
        f.session.onVerification(sid, FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.NOT_PASSED, "face-" + sid + "-999"));
        assertEquals(FaceCaptureSession.State.VERIFYING, f.session.state());
        f.advance(3_000);
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
    }

    @Test
    public void matchingFailureStageOnlyFailsItsCurrentPhase() {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onFailure(sid, FaceCaptureSession.FailureStage.CAMERA);
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        f.session.onFailure(sid, FaceCaptureSession.FailureStage.RUNTIME);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);

        Fixture camera = new Fixture();
        sid = camera.session.start();
        camera.session.onRuntimeReady(sid);
        camera.session.onFailure(sid, FaceCaptureSession.FailureStage.CAMERA);
        assertTerminal(camera, FaceCaptureSession.State.FAILURE);

        Fixture analysis = new Fixture();
        sid = analysis.reachDetecting();
        analysis.session.onFailure(sid, FaceCaptureSession.FailureStage.ANALYSIS);
        assertTerminal(analysis, FaceCaptureSession.State.FAILURE);

        Fixture jpeg = new Fixture();
        sid = jpeg.reachCapturing();
        jpeg.session.onFailure(sid, FaceCaptureSession.FailureStage.JPEG);
        assertTerminal(jpeg, FaceCaptureSession.State.FAILURE);

        Fixture verification = new Fixture();
        sid = verification.reachVerifying();
        verification.session.onFailure(sid, FaceCaptureSession.FailureStage.VERIFICATION);
        assertTerminal(verification, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void frameFailureClaimRequiresExactInFlightFrameAndIsOneShot() {
        Fixture analysis = new Fixture();
        long analysisSession = analysis.reachDetecting();
        AtomicInteger analysisClaims = new AtomicInteger();

        assertFalse(analysis.session.failFrameIfCurrent(
                analysisSession, 999L, FaceCaptureSession.FailureStage.ANALYSIS,
                analysisClaims::incrementAndGet));
        assertEquals(FaceCaptureSession.State.DETECTING, analysis.session.state());
        assertEquals(0, analysisClaims.get());
        assertTrue(analysis.session.failFrameIfCurrent(
                analysisSession, 1L, FaceCaptureSession.FailureStage.ANALYSIS,
                analysisClaims::incrementAndGet));
        assertFalse(analysis.session.failFrameIfCurrent(
                analysisSession, 1L, FaceCaptureSession.FailureStage.ANALYSIS,
                analysisClaims::incrementAndGet));
        assertEquals(1, analysisClaims.get());
        assertTerminal(analysis, FaceCaptureSession.State.FAILURE);

        Fixture jpeg = new Fixture();
        long jpegSession = jpeg.reachCapturing();
        AtomicInteger jpegClaims = new AtomicInteger();

        assertFalse(jpeg.session.failFrameIfCurrent(
                jpegSession, 2L, FaceCaptureSession.FailureStage.JPEG,
                jpegClaims::incrementAndGet));
        assertEquals(FaceCaptureSession.State.CAPTURING, jpeg.session.state());
        assertEquals(0, jpegClaims.get());
        assertTrue(jpeg.session.failFrameIfCurrent(
                jpegSession, 3L, FaceCaptureSession.FailureStage.JPEG,
                jpegClaims::incrementAndGet));
        assertFalse(jpeg.session.failFrameIfCurrent(
                jpegSession, 3L, FaceCaptureSession.FailureStage.JPEG,
                jpegClaims::incrementAndGet));
        assertEquals(1, jpegClaims.get());
        assertTerminal(jpeg, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void frameFailureRaceWithCancelOrTimeoutHasOneTerminalOwner() throws Exception {
        for (int iteration = 0; iteration < 25; iteration++) {
            Fixture cancelRace = new Fixture();
            long cancelSession = cancelRace.reachDetecting();
            AtomicInteger cancelClaims = new AtomicInteger();
            CountDownLatch cancelStart = new CountDownLatch(1);
            Thread failure = new Thread(() -> {
                await(cancelStart);
                cancelRace.session.failFrameIfCurrent(
                        cancelSession, 1L, FaceCaptureSession.FailureStage.ANALYSIS,
                        cancelClaims::incrementAndGet);
            });
            Thread cancel = new Thread(() -> {
                await(cancelStart);
                cancelRace.session.cancel();
            });
            failure.start();
            cancel.start();
            cancelStart.countDown();
            failure.join(1_000L);
            cancel.join(1_000L);

            assertFalse(failure.isAlive());
            assertFalse(cancel.isAlive());
            assertEquals(1, cancelRace.listener.terminalCount.get());
            assertEquals(1, cancelRace.actions.stopCalls);
            if (cancelRace.session.state() == FaceCaptureSession.State.FAILURE) {
                assertEquals(1, cancelClaims.get());
            } else {
                assertEquals(FaceCaptureSession.State.CANCELLED, cancelRace.session.state());
                assertEquals(0, cancelClaims.get());
            }

            Fixture timeoutRace = new Fixture();
            long timeoutSession = timeoutRace.reachCapturing();
            timeoutRace.clock.now += 3_000L;
            AtomicInteger timeoutClaims = new AtomicInteger();
            CountDownLatch timeoutStart = new CountDownLatch(1);
            Thread jpegFailure = new Thread(() -> {
                await(timeoutStart);
                timeoutRace.session.failFrameIfCurrent(
                        timeoutSession, 3L, FaceCaptureSession.FailureStage.JPEG,
                        timeoutClaims::incrementAndGet);
            });
            Thread timeout = new Thread(() -> {
                await(timeoutStart);
                timeoutRace.scheduler.runDue();
            });
            jpegFailure.start();
            timeout.start();
            timeoutStart.countDown();
            jpegFailure.join(1_000L);
            timeout.join(1_000L);

            assertFalse(jpegFailure.isAlive());
            assertFalse(timeout.isAlive());
            assertEquals(1, timeoutRace.listener.terminalCount.get());
            assertEquals(1, timeoutRace.actions.stopCalls);
            if (timeoutRace.session.state() == FaceCaptureSession.State.FAILURE) {
                assertEquals(1, timeoutClaims.get());
            } else {
                assertEquals(FaceCaptureSession.State.TIMEOUT, timeoutRace.session.state());
                assertEquals(0, timeoutClaims.get());
            }
        }
    }

    @Test
    public void frameFailureClaimHookPrecedesCleanupWhenConcurrentWorkFinishes()
            throws Exception {
        Fixture fixture = new Fixture();
        long sessionId = fixture.reachDetecting();
        CountDownLatch clockRead = new CountDownLatch(1);
        CountDownLatch allowClock = new CountDownLatch(1);
        fixture.clock.readCaptured = clockRead;
        fixture.clock.allowReadReturn = allowClock;
        Thread preview = new Thread(() ->
                fixture.session.onPreviewFrame(sessionId, fixture.frame(2L)));
        preview.start();
        assertTrue(clockRead.await(1L, TimeUnit.SECONDS));

        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch allowHook = new CountDownLatch(1);
        AtomicBoolean claimed = new AtomicBoolean();
        Thread failure = new Thread(() -> claimed.set(fixture.session.failFrameIfCurrent(
                sessionId, 1L, FaceCaptureSession.FailureStage.ANALYSIS,
                () -> {
                    hookEntered.countDown();
                    await(allowHook);
                })));
        failure.start();
        assertTrue(hookEntered.await(1L, TimeUnit.SECONDS));

        allowClock.countDown();
        preview.join(1_000L);
        int terminalBeforeHookFinished = fixture.listener.terminalCount.get();
        allowHook.countDown();
        failure.join(1_000L);

        assertFalse(preview.isAlive());
        assertFalse(failure.isAlive());
        assertTrue(claimed.get());
        assertEquals("terminal cleanup cannot overtake the claimed failure hook",
                0, terminalBeforeHookFinished);
        assertTerminal(fixture, FaceCaptureSession.State.FAILURE);
    }

    @Test
    public void actionThrowNullAndSchedulerRejectionFailClosed() {
        Fixture thrown = new Fixture();
        thrown.actions.throwPrepare = true;
        thrown.session.start();
        assertTerminal(thrown, FaceCaptureSession.State.FAILURE);

        Fixture nullAction = new Fixture();
        nullAction.actions.nullPrepare = true;
        nullAction.session.start();
        assertTerminal(nullAction, FaceCaptureSession.State.FAILURE);

        Fixture nullTimer = new Fixture();
        nullTimer.scheduler.rejectNext = true;
        nullTimer.session.start();
        assertTerminal(nullTimer, FaceCaptureSession.State.FAILURE);
        assertEquals(0, nullTimer.actions.prepareCalls);
    }

    @Test
    public void synchronousCallbacksBeforeReturnCancelLateHandlesWithoutDeadlock() {
        Fixture f = new Fixture();
        f.actions.syncRuntimeReady = true;
        f.actions.syncCameraReady = true;
        long sid = f.session.start();
        assertEquals(sid, f.session.activeSessionId());
        assertEquals(1, f.actions.prepareToken.cancelCount.get());
        assertEquals(1, f.actions.cameraToken.cancelCount.get());
        f.session.onPreviewFrame(sid, f.frame(1));
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.session.cancel();

        Fixture timer = new Fixture();
        timer.scheduler.runNextSynchronously = true;
        timer.session.start();
        assertTerminal(timer, FaceCaptureSession.State.TIMEOUT);
        assertEquals(1, timer.scheduler.lastReturned.cancelCount.get());
        assertEquals(0, timer.actions.prepareCalls);
    }

    @Test
    public void synchronousCallbackWinsEvenWhenActionThenReturnsNull() {
        Fixture f = new Fixture();
        f.actions.syncRuntimeReady = true;
        f.actions.nullPrepare = true;
        long sid = f.session.start();
        assertEquals(sid, f.session.activeSessionId());
        assertEquals(FaceCaptureSession.State.PREPARING, f.session.state());
        assertEquals(1, f.actions.cameraCalls);
        f.session.cancel();
    }

    @Test
    public void synchronousCameraReadyWinsWhenActionThenReturnsNullOrThrows() {
        Fixture nullHandle = new Fixture();
        nullHandle.actions.syncCameraReady = true;
        nullHandle.actions.nullCamera = true;
        long nullSid = nullHandle.session.start();
        nullHandle.session.onRuntimeReady(nullSid);
        assertEquals(FaceCaptureSession.State.PREPARING, nullHandle.session.state());
        assertEquals(nullSid, nullHandle.session.activeSessionId());
        nullHandle.session.onPreviewFrame(nullSid, nullHandle.frame(1));
        assertEquals(FaceCaptureSession.State.DETECTING, nullHandle.session.state());
        nullHandle.session.cancel();

        Fixture thrownAfterReady = new Fixture();
        thrownAfterReady.actions.syncCameraReady = true;
        thrownAfterReady.actions.throwCameraAfterCallback = true;
        long throwSid = thrownAfterReady.session.start();
        thrownAfterReady.session.onRuntimeReady(throwSid);
        assertEquals(FaceCaptureSession.State.PREPARING, thrownAfterReady.session.state());
        assertEquals(throwSid, thrownAfterReady.session.activeSessionId());
        thrownAfterReady.session.onPreviewFrame(throwSid, thrownAfterReady.frame(1));
        assertEquals(FaceCaptureSession.State.DETECTING, thrownAfterReady.session.state());
        thrownAfterReady.session.cancel();
    }

    @Test
    public void cancelWhileStartIsResettingCannotBeLost() throws Exception {
        Fixture f = new Fixture();
        AtomicLong returnedSessionId = new AtomicLong();
        Thread starter;
        synchronized (f.qualityGate) {
            starter = new Thread(() -> returnedSessionId.set(f.session.start()));
            starter.start();
            awaitBooleanField(f.session, "starting", true);
            f.session.cancel();
        }
        starter.join(1000);
        assertFalse(starter.isAlive());
        assertTrue(returnedSessionId.get() > 0L);
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertEquals(0, f.actions.prepareCalls);
    }

    @Test
    public void sessionIdExhaustionFailsClosedWithoutWrappingOrReuse() throws Exception {
        Fixture f = new Fixture();
        setLongField(f.session, "nextSessionId", Long.MAX_VALUE - 1L);
        long last = f.session.start();
        assertEquals(Long.MAX_VALUE, last);
        f.session.cancel();
        assertEquals(0L, f.session.start());
        assertEquals(FaceCaptureSession.State.CANCELLED, f.session.state());
        assertEquals(1, f.actions.prepareCalls);
        assertEquals(1, f.actions.stopCalls);
    }

    @Test
    public void qualityGateResetRuntimeExceptionPublishesFailureAndCleansTheSession() {
        Fixture f = new Fixture();
        setObjectField(f.session, "qualityGate", null);
        long sid = f.session.start();
        assertTrue(sid > 0L);
        assertTerminal(f, FaceCaptureSession.State.FAILURE);
        assertEquals(0, f.actions.prepareCalls);
    }

    @Test
    public void cancellationWaitsForInProgressCameraOpenThenStopsTheOpenedCamera() throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.actions.cameraActionEntered = new CountDownLatch(1);
        f.actions.allowCameraAction = new CountDownLatch(1);
        Thread opening = new Thread(() -> f.session.onRuntimeReady(sid));
        opening.start();
        assertTrue(f.actions.cameraActionEntered.await(1, TimeUnit.SECONDS));
        f.session.cancel();
        assertEquals(0, f.actions.stopCalls);
        f.actions.allowCameraAction.countDown();
        opening.join(1000);
        assertFalse(opening.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertFalse(f.actions.cameraOpened);
    }

    @Test
    public void cancellationWaitsForAcceptedCameraReadyToCancelItsDetachedHandle()
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.actions.cameraToken.cancelEntered = new CountDownLatch(1);
        f.actions.cameraToken.allowCancel = new CountDownLatch(1);

        Thread ready = new Thread(() -> f.session.onCameraReady(sid));
        ready.start();
        assertTrue(f.actions.cameraToken.cancelEntered.await(1, TimeUnit.SECONDS));
        f.session.cancel();
        assertEquals(0, f.listener.terminalCount.get());
        assertEquals(0L, f.session.start());

        f.actions.cameraToken.allowCancel.countDown();
        ready.join(1000);
        assertFalse(ready.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertTrue(f.session.start() > sid);
        f.session.cancel();
    }

    @Test
    public void detectionDeadlineAndRateStartBeforeAndAfterBlockingStateNotificationExactly() throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        f.listener.blockState = FaceCaptureSession.State.DETECTING;
        f.listener.stateEntered = new CountDownLatch(1);
        f.listener.allowState = new CountDownLatch(1);
        Thread firstFrame = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(1)));
        firstFrame.start();
        assertTrue(f.listener.stateEntered.await(1, TimeUnit.SECONDS));
        assertEquals(10_000L, f.scheduler.firstActiveDueAfter(f.clock.now));
        f.clock.now = 500L;
        f.listener.allowState.countDown();
        firstFrame.join(1000);
        assertEquals(1, f.actions.analyzeCalls);
        f.actions.completeObservation(validObservation());
        f.clock.now = 599L;
        f.session.onPreviewFrame(sid, f.frame(2));
        f.scheduler.runDue();
        assertEquals(1, f.actions.analyzeCalls);
        f.clock.now = 600L;
        f.scheduler.runDue();
        assertEquals(2, f.actions.analyzeCalls);
        f.session.cancel();
    }

    @Test
    public void synchronousVerifierTimeoutZerosOriginalBeforeTerminalCallback() {
        Fixture f = new Fixture();
        f.reachCapturing();
        byte[] jpeg = jpeg();
        f.listener.bytesToCheckAtTerminal = jpeg;
        f.scheduler.runNextSynchronously = true;
        f.actions.completeJpeg(jpeg);
        assertTerminal(f, FaceCaptureSession.State.TIMEOUT);
        assertTrue(f.listener.zeroAtTerminal);
        assertArrayEquals(new byte[jpeg.length], jpeg);
        assertEquals(0, f.actions.verifyCalls);
    }

    @Test
    public void terminalWaitsForClaimedJpegCallbackToZeroItsOwnedArray() throws Exception {
        Fixture f = new Fixture();
        f.reachCapturing();
        byte[] jpeg = jpeg();
        f.listener.bytesToCheckAtTerminal = jpeg;
        FaceCapture current = (FaceCapture) getObjectField(f.session, "capture");
        Thread callback;
        synchronized (current) {
            callback = new Thread(() -> f.actions.completeJpeg(jpeg));
            callback.start();
            awaitBooleanField(f.session, "jpegProcessing", true);
            f.session.cancel();
            assertEquals(0, f.listener.terminalCount.get());
        }
        callback.join(1000);
        assertFalse(callback.isAlive());
        assertTerminal(f, FaceCaptureSession.State.CANCELLED);
        assertArrayEquals(new byte[jpeg.length], jpeg);
        assertTrue(f.listener.zeroAtTerminal);
    }

    @Test
    public void terminalRaceIsFirstWinsAndCleansOnce() throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachVerifying();
        FaceVerificationResult passed = passed(f.actions.verifyRequestId);
        CountDownLatch start = new CountDownLatch(1);
        Thread cancel = new Thread(() -> { await(start); f.session.cancel(); });
        Thread success = new Thread(() -> { await(start); f.session.onVerification(sid, passed); });
        cancel.start();
        success.start();
        start.countDown();
        cancel.join(1000);
        success.join(1000);
        assertFalse(cancel.isAlive());
        assertFalse(success.isAlive());
        assertEquals(1, f.listener.terminalCount.get());
        assertEquals(1, f.actions.stopCalls);
        assertTrue(f.session.state() == FaceCaptureSession.State.CANCELLED
                || f.session.state() == FaceCaptureSession.State.SUCCESS);
    }

    @Test
    public void verifierTimeoutVsSuccessIsFirstWins() throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachVerifying();
        FaceVerificationResult passed = passed(f.actions.verifyRequestId);
        f.clock.now += 3_000;
        CountDownLatch start = new CountDownLatch(1);
        Thread timeout = new Thread(() -> { await(start); f.scheduler.runDue(); });
        Thread success = new Thread(() -> { await(start); f.session.onVerification(sid, passed); });
        timeout.start();
        success.start();
        start.countDown();
        timeout.join(1000);
        success.join(1000);
        assertEquals(1, f.listener.terminalCount.get());
        assertEquals(1, f.actions.stopCalls);
        assertTrue(f.session.state() == FaceCaptureSession.State.TIMEOUT
                || f.session.state() == FaceCaptureSession.State.SUCCESS);
    }

    @Test
    public void synchronousAnalysisEncodeAndVerifyCallbacksCancelEveryLateHandle() {
        Fixture f = new Fixture();
        f.actions.syncAnalysis = true;
        f.actions.syncEncode = true;
        f.actions.syncVerify = true;
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        f.session.onPreviewFrame(sid, f.frame(1));
        f.advance(100);
        f.session.onPreviewFrame(sid, f.frame(2));
        f.advance(100);
        f.session.onPreviewFrame(sid, f.frame(3));
        assertTerminal(f, FaceCaptureSession.State.SUCCESS);
        assertEquals(1, f.actions.analysisToken.cancelCount.get());
        assertEquals(1, f.actions.encodeToken.cancelCount.get());
        assertEquals(1, f.actions.verifyToken.cancelCount.get());
    }

    @Test
    public void nullBorrowActionsLeaveSessionFallbackCleanup() {
        Fixture analyze = new Fixture();
        analyze.actions.nullAnalyze = true;
        long sid = analyze.session.start();
        analyze.session.onRuntimeReady(sid);
        analyze.session.onCameraReady(sid);
        analyze.session.onPreviewFrame(sid, analyze.frame(1));
        assertTerminal(analyze, FaceCaptureSession.State.FAILURE);
        assertEquals(1, analyze.releaseCount(1));

        Fixture encode = new Fixture();
        sid = encode.reachDetecting();
        encode.actions.completeObservation(validObservation());
        encode.nextFrame(sid, 2, validObservation());
        encode.actions.nullEncode = true;
        encode.nextFrame(sid, 3, validObservation());
        assertTerminal(encode, FaceCaptureSession.State.FAILURE);
        assertEquals(1, encode.releaseCount(3));

        Fixture verify = new Fixture();
        sid = verify.reachCapturing();
        verify.actions.nullVerify = true;
        byte[] original = jpeg();
        verify.actions.completeJpeg(original);
        assertTerminal(verify, FaceCaptureSession.State.FAILURE);
        assertArrayEquals(new byte[original.length], original);
    }

    @Test
    public void replacementVsObservationReleasesEveryFrameExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.session.onPreviewFrame(sid, f.frame(2));
        CountDownLatch start = new CountDownLatch(1);
        Thread replace = new Thread(() -> { await(start); f.session.onPreviewFrame(sid, f.frame(3)); });
        Thread observe = new Thread(() -> { await(start); f.actions.completeObservation(validObservation()); });
        replace.start();
        observe.start();
        start.countDown();
        replace.join(1000);
        observe.join(1000);
        f.session.cancel();
        assertEquals(1, f.releaseCount(1));
        assertEquals(1, f.releaseCount(2));
        assertEquals(1, f.releaseCount(3));
    }

    @Test
    public void qualityDecisionCompletionBlocksParallelNextAnalysis() throws Exception {
        Fixture f = new Fixture();
        long sid = f.reachDetecting();
        f.listener.decisionEntered = new CountDownLatch(1);
        f.listener.allowDecision = new CountDownLatch(1);
        Thread completion = new Thread(() -> f.actions.completeObservation(validObservation()));
        completion.start();
        assertTrue(f.listener.decisionEntered.await(1, TimeUnit.SECONDS));
        f.clock.now = 100;
        f.session.onPreviewFrame(sid, f.frame(2));
        assertEquals(1, f.actions.analyzeCalls);
        f.listener.allowDecision.countDown();
        completion.join(1000);
        f.scheduler.runDue();
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(2L, f.actions.analysisFrameId);
        f.session.cancel();
    }

    @Test
    public void cancelledObservationCannotContaminateTheNextSessionsQualityGate() throws Exception {
        Fixture f = new Fixture();
        long first = f.reachDetecting();
        Thread oldObservation;
        synchronized (f.qualityGate) {
            oldObservation = new Thread(() -> f.actions.completeObservation(validObservation()));
            oldObservation.start();
            awaitBooleanField(f.session, "analysisCompleting", true);
            f.session.cancel();
            assertEquals(0L, f.session.start());
        }
        oldObservation.join(1000);
        assertFalse(oldObservation.isAlive());
        assertEquals(0, f.listener.decisions.size());

        long second = f.session.start();
        assertTrue(second > first);
        f.session.onRuntimeReady(second);
        f.session.onCameraReady(second);
        f.session.onPreviewFrame(second, f.frame(2));
        f.actions.completeObservation(validObservation());
        f.nextFrame(second, 3, validObservation());
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        f.nextFrame(second, 4, validObservation());
        assertEquals(FaceCaptureSession.State.CAPTURING, f.session.state());
        f.session.cancel();
    }

    @Test
    public void terminalCannotBeFollowedByAStaleStateNotification() throws Exception {
        Fixture f = new Fixture();
        f.reachCapturing();
        f.actions.encodeToken.cancelEntered = new CountDownLatch(1);
        f.actions.encodeToken.allowCancel = new CountDownLatch(1);
        Thread jpeg = new Thread(() -> f.actions.completeJpeg(jpeg()));
        jpeg.start();
        assertTrue(f.actions.encodeToken.cancelEntered.await(1, TimeUnit.SECONDS));
        f.session.cancel();
        f.actions.encodeToken.allowCancel.countDown();
        jpeg.join(1000);
        assertEquals(FaceCaptureSession.State.CANCELLED,
                f.listener.states.get(f.listener.states.size() - 1));
        assertEquals(1, f.listener.terminalCount.get());
    }

    @Test
    public void newStartWaitsUntilPriorTerminalCleanupAndNotificationFinish() throws Exception {
        Fixture f = new Fixture();
        long first = f.session.start();
        f.actions.stopEntered = new CountDownLatch(1);
        f.actions.allowStop = new CountDownLatch(1);
        Thread cancel = new Thread(f.session::cancel);
        cancel.start();
        assertTrue(f.actions.stopEntered.await(1, TimeUnit.SECONDS));
        assertEquals(0L, f.session.start());
        f.actions.allowStop.countDown();
        cancel.join(1000);
        long second = f.session.start();
        assertTrue(second > first);
        f.session.cancel();
    }

    @Test
    public void runtimeExceptionsDuringCleanupAndCallbacksDoNotBlockFreshSession() {
        Fixture f = new Fixture();
        f.listener.throwCallbacks = true;
        f.actions.throwStop = true;
        long first = f.session.start();
        f.actions.prepareToken.throwCancel = true;
        f.session.cancel();
        assertEquals(FaceCaptureSession.State.CANCELLED, f.session.state());
        long second = f.session.start();
        assertTrue(second > first);
        f.session.cancel();
        assertEquals(2, f.actions.stopCalls);
    }

    private static void assertTerminal(Fixture f, FaceCaptureSession.State expected) {
        assertEquals(expected, f.session.state());
        assertEquals(0L, f.session.activeSessionId());
        assertEquals(1, f.listener.terminalCount.get());
        assertEquals(1, f.actions.stopCalls);
    }

    private static void assertOldAnalysisCompletionCannotFailNewer(boolean throwAfterCallback)
            throws Exception {
        Fixture f = new Fixture();
        long sid = f.session.start();
        f.session.onRuntimeReady(sid);
        f.session.onCameraReady(sid);
        f.actions.firstAnalysisEntered = new CountDownLatch(1);
        f.actions.allowFirstAnalysis = new CountDownLatch(1);
        f.actions.firstAnalysisCallbackThenNull = !throwAfterCallback;
        f.actions.firstAnalysisCallbackThenThrow = throwAfterCallback;

        Thread first = new Thread(() -> f.session.onPreviewFrame(sid, f.frame(1)));
        first.start();
        assertTrue(f.actions.firstAnalysisEntered.await(1, TimeUnit.SECONDS));
        f.clock.now = 100L;
        f.session.onPreviewFrame(sid, f.frame(2));
        f.actions.allowFirstAnalysis.countDown();
        first.join(1000);

        assertFalse(first.isAlive());
        assertEquals(FaceCaptureSession.State.DETECTING, f.session.state());
        assertEquals(2, f.actions.analyzeCalls);
        assertEquals(2L, f.actions.analysisFrameId);
        f.session.cancel();
    }

    private static FaceVerificationResult passed(String requestId) {
        try {
            Method method = FaceVerificationResult.class.getDeclaredMethod("passed",
                    FaceVerificationSource.class, String.class, String.class, String.class,
                    long.class, String.class, String.class, long.class);
            method.setAccessible(true);
            return (FaceVerificationResult) method.invoke(null, FaceVerificationSource.LOCAL_DEMO,
                    requestId, "credential", "0123456789abcdef0123456789abcdef",
                    Long.MAX_VALUE, "device", "process", 1L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static FaceObservation validObservation() {
        return new FaceObservation(640, 480, 1, .9f, 320, 240, 100, 100,
                0, 0, 0, .1f, .9f, .1f, .1f, .1f, .1f, .1f, .1f, .1f, 1f, .9f);
    }

    private static FaceObservation noFaceObservation() {
        return new FaceObservation(640, 480, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static byte[] jpeg() { return jpegTemplate(); }

    private static byte[] jpegTemplate() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, 4, 5, 6, 7, (byte) 0xff, (byte) 0xd9};
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitBooleanField(Object target, String name, boolean expected) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadline) {
                synchronized (target) {
                    if (field.getBoolean(target) == expected) return;
                }
                Thread.yield();
            }
            fail("Timed out waiting for " + name + "=" + expected);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) return;
            if (!thread.isAlive()) {
                fail("Thread terminated before reaching " + expected);
            }
            Thread.yield();
        }
        fail("Timed out waiting for thread state " + expected
                + ", actual=" + thread.getState());
    }

    private static void setLongField(Object target, String name, long value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            synchronized (target) {
                field.setLong(target, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            synchronized (target) {
                field.setBoolean(target, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean invokeArmPumpTimer(FaceCaptureSession session, long sid,
            long delayMillis, long reservation) {
        try {
            Method method = FaceCaptureSession.class.getDeclaredMethod("armPumpTimer",
                    long.class, long.class, long.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(session, sid, delayMillis, reservation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invokePump(FaceCaptureSession session, long sid) {
        try {
            Method method = FaceCaptureSession.class.getDeclaredMethod("pump", long.class);
            method.setAccessible(true);
            method.invoke(session, sid);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setObjectField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            synchronized (target) {
                field.set(target, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object getObjectField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            synchronized (target) {
                return field.get(target);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class BlockingLinearizationProbe
            implements FaceCaptureSession.LinearizationProbe {
        final Event target;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch allow = new CountDownLatch(1);
        final AtomicBoolean holdsLockViolation = new AtomicBoolean();
        volatile FaceCaptureSession session;

        BlockingLinearizationProbe(Event target) {
            this.target = target;
        }

        @Override
        public void afterTerminalClaim(Event event) {
            if (Thread.holdsLock(session)) {
                holdsLockViolation.set(true);
            }
            if (event != target) return;
            entered.countDown();
            try {
                allow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final FakeScheduler scheduler = new FakeScheduler(clock);
        final FakeActions actions = new FakeActions();
        final RecordingListener listener = new RecordingListener();
        final FaceFrameQualityGate qualityGate = new FaceFrameQualityGate();
        final FaceCaptureSession session;
        final List<Release> releases = Collections.synchronizedList(new ArrayList<Release>());
        volatile long blockReleaseKey = Long.MIN_VALUE;
        volatile CountDownLatch releaseEntered;
        volatile CountDownLatch allowRelease;

        Fixture() {
            this(null);
        }

        Fixture(long verificationTimeoutMillis) {
            session = new FaceCaptureSession(clock, scheduler, actions, qualityGate, listener,
                    verificationTimeoutMillis);
            actions.session = session;
        }

        Fixture(FaceCaptureSession.LinearizationProbe probe) {
            session = new FaceCaptureSession(clock, scheduler, actions, qualityGate, listener,
                    probe);
            actions.session = session;
        }

        void advance(long delta) {
            clock.now += delta;
            scheduler.runDue();
        }

        long reachDetecting() {
            long sid = session.start();
            session.onRuntimeReady(sid);
            session.onCameraReady(sid);
            session.onPreviewFrame(sid, frame(1));
            assertEquals(FaceCaptureSession.State.DETECTING, session.state());
            return sid;
        }

        long reachCapturing() {
            long sid = reachDetecting();
            actions.completeObservation(validObservation());
            nextFrame(sid, 2, validObservation());
            nextFrame(sid, 3, validObservation());
            assertEquals(FaceCaptureSession.State.CAPTURING, session.state());
            return sid;
        }

        long reachVerifying() {
            long sid = reachCapturing();
            actions.completeJpeg(jpeg());
            assertEquals(FaceCaptureSession.State.VERIFYING, session.state());
            return sid;
        }

        void nextFrame(long sid, long id, FaceObservation observation) {
            advance(100);
            session.onPreviewFrame(sid, frame(id));
            actions.completeObservation(observation);
        }

        FaceFrame frame(long id) { return frame(id, id); }

        FaceFrame frame(long id, long releaseKey) {
            byte[] bytes = new byte[] {1, 2, 3, 4, 5, 6};
            return new FaceFrame(id, bytes, 2, 2, 0, 1, 90, false,
                    zeroed -> {
                        if (releaseKey == blockReleaseKey) {
                            if (releaseEntered != null) releaseEntered.countDown();
                            if (allowRelease != null) await(allowRelease);
                        }
                        releases.add(new Release(releaseKey, zeroed));
                    });
        }

        int releaseCount(long id) {
            synchronized (releases) {
                int count = 0;
                for (Release release : releases) if (release.id == id) count++;
                return count;
            }
        }
    }

    private static final class Release {
        final long id;
        final byte[] bytes;
        Release(long id, byte[] bytes) { this.id = id; this.bytes = bytes; }
    }

    private static final class FakeClock implements FaceCaptureSession.Clock {
        volatile long now;
        volatile CountDownLatch readCaptured;
        volatile CountDownLatch allowReadReturn;
        volatile long[] scriptedValues;
        final AtomicInteger scriptedIndex = new AtomicInteger();

        @Override public long elapsedMillis() {
            long[] values = scriptedValues;
            int index = scriptedIndex.getAndIncrement();
            long value = values != null && index < values.length ? values[index] : now;
            CountDownLatch captured;
            CountDownLatch allow;
            synchronized (this) {
                captured = readCaptured;
                allow = allowReadReturn;
                readCaptured = null;
                allowReadReturn = null;
            }
            if (captured != null) {
                captured.countDown();
                if (allow != null) await(allow);
            }
            return value;
        }
    }

    private static final class Token implements FaceCaptureSession.Cancellable {
        final AtomicInteger cancelCount = new AtomicInteger();
        volatile boolean cancelled;
        volatile boolean throwCancel;
        volatile CountDownLatch cancelEntered;
        volatile CountDownLatch allowCancel;
        @Override public void cancel() {
            cancelled = true;
            cancelCount.incrementAndGet();
            if (cancelEntered != null) cancelEntered.countDown();
            if (allowCancel != null) await(allowCancel);
            if (throwCancel) throw new IllegalStateException("test cancel");
        }
    }

    private static final class FakeScheduler implements FaceCaptureSession.Scheduler {
        final FakeClock clock;
        final List<Scheduled> tasks = new ArrayList<>();
        boolean rejectNext;
        boolean runNextSynchronously;
        volatile CountDownLatch scheduleEntered;
        volatile CountDownLatch allowSchedule;
        volatile CountDownLatch scheduleCompleted;
        Token lastReturned;
        int scheduleCalls;

        FakeScheduler(FakeClock clock) { this.clock = clock; }

        @Override public FaceCaptureSession.Cancellable schedule(Runnable task, long delayMillis) {
            scheduleCalls++;
            CountDownLatch entered = scheduleEntered;
            CountDownLatch allow = allowSchedule;
            if (entered != null) {
                entered.countDown();
                if (allow != null) await(allow);
            }
            if (rejectNext) { rejectNext = false; return null; }
            Token token = new Token();
            lastReturned = token;
            if (runNextSynchronously) {
                runNextSynchronously = false;
                task.run();
                if (scheduleCompleted != null) scheduleCompleted.countDown();
                return token;
            }
            synchronized (tasks) { tasks.add(new Scheduled(clock.now + delayMillis, task, token)); }
            if (scheduleCompleted != null) scheduleCompleted.countDown();
            return token;
        }

        void runDue() {
            while (true) {
                Scheduled due = null;
                synchronized (tasks) {
                    for (Scheduled task : tasks) {
                        if (!task.token.cancelled && task.at <= clock.now
                                && (due == null || task.at < due.at)) due = task;
                    }
                    if (due != null) tasks.remove(due);
                }
                if (due == null) return;
                due.task.run();
            }
        }


        long firstActiveDueAfter(long now) {
            synchronized (tasks) {
                long first = Long.MAX_VALUE;
                for (Scheduled task : tasks) {
                    if (!task.token.cancelled && task.at >= now && task.at < first) first = task.at;
                }
                return first;
            }
        }

        Token activeTokenAt(long at) {
            synchronized (tasks) {
                for (Scheduled task : tasks) {
                    if (!task.token.cancelled && task.at == at) return task.token;
                }
            }
            throw new AssertionError("No active timer at " + at);
        }
    }

    private static final class Scheduled {
        final long at;
        final Runnable task;
        final Token token;
        Scheduled(long at, Runnable task, Token token) { this.at = at; this.task = task; this.token = token; }
    }

    private static final class FakeActions implements FaceCaptureSession.Actions {
        FaceCaptureSession session;
        int prepareCalls;
        int cameraCalls;
        int analyzeCalls;
        int encodeCalls;
        int verifyCalls;
        volatile long prepareSessionId;
        volatile int stopCalls;
        long analysisSessionId;
        long analysisFrameId;
        long encodeSessionId;
        long encodeFrameId;
        long verifySessionId;
        String verifyRequestId;
        byte[] copiedJpeg;
        FaceFrame.Borrow analysisBorrow;
        FaceFrame.Borrow encodeBorrow;
        Token prepareToken;
        Token cameraToken;
        Token analysisToken;
        Token encodeToken;
        Token verifyToken;
        boolean throwPrepare;
        boolean nullPrepare;
        boolean syncRuntimeReady;
        boolean syncCameraReady;
        boolean nullCamera;
        boolean throwCameraAfterCallback;
        boolean syncAnalysis;
        boolean syncEncode;
        boolean syncVerify;
        boolean nullAnalyze;
        boolean nullEncode;
        boolean nullVerify;
        boolean firstAnalysisCallbackThenNull;
        boolean firstAnalysisCallbackThenThrow;
        boolean throwStop;
        volatile CountDownLatch firstAnalysisEntered;
        volatile CountDownLatch allowFirstAnalysis;
        volatile CountDownLatch stopEntered;
        volatile CountDownLatch allowStop;
        volatile CountDownLatch cameraActionEntered;
        volatile CountDownLatch allowCameraAction;
        volatile boolean cameraOpened;

        @Override public FaceCaptureSession.Cancellable prepareRuntime(long sessionId) {
            prepareCalls++;
            prepareSessionId = sessionId;
            if (throwPrepare) throw new IllegalStateException("test prepare");
            if (syncRuntimeReady) session.onRuntimeReady(sessionId);
            if (nullPrepare) return null;
            prepareToken = new Token();
            return prepareToken;
        }

        @Override public FaceCaptureSession.Cancellable startCamera(long sessionId) {
            cameraCalls++;
            if (cameraActionEntered != null) cameraActionEntered.countDown();
            if (allowCameraAction != null) await(allowCameraAction);
            cameraOpened = true;
            if (syncCameraReady) session.onCameraReady(sessionId);
            if (throwCameraAfterCallback) throw new IllegalStateException("test camera");
            if (nullCamera) return null;
            cameraToken = new Token();
            return cameraToken;
        }

        @Override public FaceCaptureSession.Cancellable analyze(long sessionId, long frameId,
                FaceFrame.Borrow borrowedFrame) {
            analyzeCalls++;
            analysisSessionId = sessionId;
            analysisFrameId = frameId;
            analysisBorrow = borrowedFrame;
            analysisToken = new Token();
            if (analyzeCalls == 1
                    && (firstAnalysisCallbackThenNull || firstAnalysisCallbackThenThrow)) {
                if (firstAnalysisEntered != null) firstAnalysisEntered.countDown();
                if (allowFirstAnalysis != null) await(allowFirstAnalysis);
                analysisBorrow = null;
                borrowedFrame.close();
                session.onObservation(sessionId, frameId, noFaceObservation());
                if (firstAnalysisCallbackThenThrow) {
                    throw new IllegalStateException("test analysis after callback");
                }
                return null;
            }
            if (syncAnalysis) {
                analysisBorrow = null;
                borrowedFrame.close();
                session.onObservation(sessionId, frameId, validObservation());
            }
            if (nullAnalyze) return null;
            return analysisToken;
        }

        @Override public FaceCaptureSession.Cancellable encode(long sessionId, long frameId,
                FaceFrame.Borrow borrowedFrame) {
            encodeCalls++;
            encodeSessionId = sessionId;
            encodeFrameId = frameId;
            encodeBorrow = borrowedFrame;
            encodeToken = new Token();
            if (syncEncode) {
                encodeBorrow = null;
                borrowedFrame.close();
                session.onJpegReady(sessionId, frameId, jpeg());
            }
            if (nullEncode) return null;
            return encodeToken;
        }

        @Override public FaceCaptureSession.Cancellable verify(long sessionId, String requestId,
                FaceCapture.JpegBorrow borrowedJpeg) {
            verifyCalls++;
            verifySessionId = sessionId;
            verifyRequestId = requestId;
            copiedJpeg = borrowedJpeg.jpeg().clone();
            borrowedJpeg.close();
            verifyToken = new Token();
            if (syncVerify) session.onVerification(sessionId, passed(requestId));
            if (nullVerify) return null;
            return verifyToken;
        }

        @Override public void stopCamera(long sessionId) {
            stopCalls++;
            cameraOpened = false;
            if (stopEntered != null) stopEntered.countDown();
            if (allowStop != null) {
                await(allowStop);
                stopEntered = null;
                allowStop = null;
            }
            close(analysisBorrow);
            close(encodeBorrow);
            if (throwStop) throw new IllegalStateException("test stop");
        }

        void completeObservation(FaceObservation observation) {
            FaceFrame.Borrow borrow = analysisBorrow;
            analysisBorrow = null;
            close(borrow);
            session.onObservation(analysisSessionId, analysisFrameId, observation);
        }

        void completeJpeg(byte[] jpeg) {
            FaceFrame.Borrow borrow = encodeBorrow;
            encodeBorrow = null;
            close(borrow);
            session.onJpegReady(encodeSessionId, encodeFrameId, jpeg);
        }

        void completeVerification(FaceVerificationResult result) {
            session.onVerification(verifySessionId, result);
        }

        private static void close(AutoCloseable closeable) {
            if (closeable == null) return;
            try { closeable.close(); } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static final class RecordingListener implements FaceCaptureSession.Listener {
        final List<FaceCaptureSession.State> states = Collections.synchronizedList(new ArrayList<FaceCaptureSession.State>());
        final List<FaceFrameQualityGate.Decision> decisions = Collections.synchronizedList(new ArrayList<FaceFrameQualityGate.Decision>());
        final AtomicInteger terminalCount = new AtomicInteger();
        volatile boolean throwCallbacks;
        volatile byte[] bytesToCheckAtTerminal;
        volatile boolean zeroAtTerminal;
        volatile CountDownLatch decisionEntered;
        volatile CountDownLatch allowDecision;
        volatile FaceCaptureSession.State blockState;
        volatile CountDownLatch stateEntered;
        volatile CountDownLatch allowState;

        @Override public void onStateChanged(long sessionId, FaceCaptureSession.State state) {
            states.add(state);
            if (state == blockState && stateEntered != null && allowState != null) {
                stateEntered.countDown();
                await(allowState);
            }
            if (throwCallbacks) throw new IllegalStateException("test listener");
        }

        @Override public void onQualityDecision(long sessionId, FaceFrameQualityGate.Decision decision) {
            decisions.add(decision);
            CountDownLatch entered = decisionEntered;
            CountDownLatch allow = allowDecision;
            if (entered != null && allow != null) {
                decisionEntered = null;
                entered.countDown();
                await(allow);
            }
            if (throwCallbacks) throw new IllegalStateException("test listener");
        }

        @Override public void onTerminal(long sessionId, FaceCaptureSession.State state,
                FaceVerificationResult result) {
            terminalCount.incrementAndGet();
            zeroAtTerminal = bytesToCheckAtTerminal == null || allZero(bytesToCheckAtTerminal);
            if (throwCallbacks) throw new IllegalStateException("test listener");
        }

        private static boolean allZero(byte[] bytes) {
            for (byte value : bytes) if (value != 0) return false;
            return true;
        }
    }
}
