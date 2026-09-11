package com.codex.lockertest.face;

import com.codex.lockertest.face.verification.FaceVerificationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FaceCaptureSession {
    public enum State {
        PREPARING, DETECTING, CAPTURING, VERIFYING,
        SUCCESS, FAILURE, CANCELLED, TIMEOUT
    }

    public enum FailureStage { RUNTIME, CAMERA, ANALYSIS, JPEG, VERIFICATION }

    public interface Cancellable { void cancel(); }
    public interface Clock { long elapsedMillis(); }
    public interface Scheduler { Cancellable schedule(Runnable task, long delayMillis); }

    public interface Actions {
        Cancellable prepareRuntime(long sessionId);
        Cancellable startCamera(long sessionId);
        Cancellable analyze(long sessionId, long frameId, FaceFrame.Borrow borrowedFrame);
        Cancellable encode(long sessionId, long frameId, FaceFrame.Borrow borrowedFrame);
        Cancellable verify(long sessionId, String requestId, FaceCapture.JpegBorrow borrowedJpeg);
        void stopCamera(long sessionId);
    }

    public interface Listener {
        void onStateChanged(long sessionId, State state);
        void onQualityDecision(long sessionId, FaceFrameQualityGate.Decision decision);
        void onTerminal(long sessionId, State state, FaceVerificationResult result);
    }

    interface LinearizationProbe {
        enum Event { ACTION_FAILURE, TIMER_EXPIRY }
        void afterTerminalClaim(Event event);
    }

    private enum TimerKind { RUNTIME, CAMERA, DETECTION, ENCODE, VERIFY, PUMP }

    private static final class TimerSlot {
        long generation;
        boolean armed;
        State expectedState;
        Cancellable handle;
    }

    private static final class Cleanup {
        final long sessionId;
        final State terminal;
        final FaceVerificationResult result;
        final List<Cancellable> cancellables = new ArrayList<>();
        LatestFaceFrameMailbox mailbox;
        FaceFrame inFlight;
        FaceCapture capture;
        FaceCapture.JpegBorrow jpegBorrow;

        Cleanup(long sessionId, State terminal, FaceVerificationResult result) {
            this.sessionId = sessionId;
            this.terminal = terminal;
            this.result = result;
        }
    }

    private static final long RUNTIME_TIMEOUT_MS = 15_000L;
    private static final long CAMERA_TIMEOUT_MS = 5_000L;
    private static final long DETECTION_TIMEOUT_MS = 10_000L;
    private static final long ENCODE_TIMEOUT_MS = 3_000L;
    public static final long DEFAULT_VERIFICATION_TIMEOUT_MILLIS = 3_000L;
    private static final long ANALYSIS_INTERVAL_MS = 100L;

    private final Clock clock;
    private final Scheduler scheduler;
    private final Actions actions;
    private final FaceFrameQualityGate qualityGate;
    private final Listener listener;
    private final LinearizationProbe linearizationProbe;
    private final long verificationTimeoutMillis;
    private final Object notificationGate = new Object();

    private final TimerSlot runtimeTimer = new TimerSlot();
    private final TimerSlot cameraTimer = new TimerSlot();
    private final TimerSlot detectionTimer = new TimerSlot();
    private final TimerSlot encodeTimer = new TimerSlot();
    private final TimerSlot verifyTimer = new TimerSlot();
    private final TimerSlot pumpTimer = new TimerSlot();

    private long nextSessionId;
    private long activeSessionId;
    private State state;
    private boolean starting;
    private boolean terminating;
    private int externalWorkInFlight;
    private Cleanup pendingCleanup;
    private boolean runtimeOutstanding;
    private boolean cameraOutstanding;
    private boolean cameraReady;
    private boolean detectionStarting;
    private LatestFaceFrameMailbox mailbox;
    private long highestFrameId;
    private long lastAnalysisStart = Long.MIN_VALUE;
    private boolean analysisInFlight;
    private boolean analysisCompleting;
    private boolean pumpClaimed;
    private boolean pumpSignalPending;
    private boolean pumpTimerScheduling;
    private long pumpScheduleGeneration;
    private FaceFrame inFlightFrame;
    private Cancellable runtimeHandle;
    private Cancellable cameraHandle;
    private Cancellable analysisHandle;
    private Cancellable encodeHandle;
    private Cancellable verifyHandle;
    private FaceCapture.JpegBorrow verificationBorrow;
    private FaceCapture capture;
    private boolean jpegProcessing;
    private String verificationRequestId;

    public FaceCaptureSession(Clock clock, Scheduler scheduler, Actions actions,
            FaceFrameQualityGate qualityGate, Listener listener) {
        this(clock, scheduler, actions, qualityGate, listener,
                DEFAULT_VERIFICATION_TIMEOUT_MILLIS, null);
    }

    public FaceCaptureSession(Clock clock, Scheduler scheduler, Actions actions,
            FaceFrameQualityGate qualityGate, Listener listener,
            long verificationTimeoutMillis) {
        this(clock, scheduler, actions, qualityGate, listener,
                verificationTimeoutMillis, null);
    }

    FaceCaptureSession(Clock clock, Scheduler scheduler, Actions actions,
            FaceFrameQualityGate qualityGate, Listener listener,
            LinearizationProbe linearizationProbe) {
        this(clock, scheduler, actions, qualityGate, listener,
                DEFAULT_VERIFICATION_TIMEOUT_MILLIS, linearizationProbe);
    }

    FaceCaptureSession(Clock clock, Scheduler scheduler, Actions actions,
            FaceFrameQualityGate qualityGate, Listener listener,
            long verificationTimeoutMillis, LinearizationProbe linearizationProbe) {
        if (clock == null || scheduler == null || actions == null
                || qualityGate == null || listener == null) {
            throw new IllegalArgumentException("session dependencies cannot be null");
        }
        if (verificationTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("verification timeout must be positive");
        }
        this.clock = clock;
        this.scheduler = scheduler;
        this.actions = actions;
        this.qualityGate = qualityGate;
        this.listener = listener;
        this.verificationTimeoutMillis = verificationTimeoutMillis;
        this.linearizationProbe = linearizationProbe;
    }

    public long start() {
        final long sid;
        synchronized (this) {
            if (activeSessionId != 0L || starting || terminating
                    || nextSessionId == Long.MAX_VALUE) return 0L;
            starting = true;
            sid = nextPositiveSessionIdLocked();
            activeSessionId = sid;
            state = State.PREPARING;
            runtimeOutstanding = true;
            cameraOutstanding = false;
            cameraReady = false;
            detectionStarting = false;
            mailbox = new LatestFaceFrameMailbox();
            highestFrameId = 0L;
            lastAnalysisStart = Long.MIN_VALUE;
            analysisInFlight = false;
            analysisCompleting = false;
            pumpClaimed = false;
            pumpSignalPending = false;
            pumpTimerScheduling = false;
            pumpScheduleGeneration++;
            inFlightFrame = null;
            capture = null;
            jpegProcessing = false;
            verificationRequestId = null;
            verificationBorrow = null;
            externalWorkInFlight++;
        }
        boolean runtimeTimerArmed = armTimer(runtimeTimer, TimerKind.RUNTIME, sid,
                State.PREPARING, RUNTIME_TIMEOUT_MS);
        boolean resetFailed = false;
        try {
            qualityGate.reset();
        } catch (RuntimeException ignored) {
            resetFailed = true;
            fail(sid);
        } finally {
            synchronized (this) {
                starting = false;
            }
            completeExternalWork();
        }
        if (resetFailed) return sid;
        if (!runtimeTimerArmed) return sid;
        synchronized (this) {
            if (activeSessionId != sid || state != State.PREPARING) return sid;
        }
        notifyState(sid, State.PREPARING);
        boolean invoke;
        synchronized (this) {
            invoke = activeSessionId == sid && state == State.PREPARING && runtimeOutstanding;
            if (invoke) externalWorkInFlight++;
        }
        if (!invoke) return sid;
        try {
            Cancellable returned = actions.prepareRuntime(sid);
            if (returned == null) {
                failIfActionOutstanding(sid, FailureStage.RUNTIME);
            } else {
                installActionHandle(sid, returned, FailureStage.RUNTIME);
            }
        } catch (RuntimeException ignored) {
            failIfActionOutstanding(sid, FailureStage.RUNTIME);
        } finally {
            completeExternalWork();
        }
        return sid;
    }

    public void onRuntimeReady(long sessionId) {
        Cancellable action;
        Cancellable timer;
        synchronized (this) {
            if (activeSessionId != sessionId || state != State.PREPARING || !runtimeOutstanding) return;
            runtimeOutstanding = false;
            cameraOutstanding = true;
            externalWorkInFlight++;
            action = runtimeHandle;
            runtimeHandle = null;
            timer = disarmLocked(runtimeTimer);
        }
        try {
            boolean timerArmed = armTimer(cameraTimer, TimerKind.CAMERA, sessionId,
                    State.PREPARING, CAMERA_TIMEOUT_MS);
            safeCancel(action);
            safeCancel(timer);
            if (!timerArmed) return;
            synchronized (this) {
                if (activeSessionId != sessionId || state != State.PREPARING
                        || !cameraOutstanding) return;
            }
            Cancellable returned = actions.startCamera(sessionId);
            if (returned == null) {
                failIfActionOutstanding(sessionId, FailureStage.CAMERA);
            } else {
                installActionHandle(sessionId, returned, FailureStage.CAMERA);
            }
        } catch (RuntimeException ignored) {
            failIfActionOutstanding(sessionId, FailureStage.CAMERA);
        } finally {
            completeExternalWork();
        }
    }

    /** Returns true only when this callback completes the current camera transition. */
    public boolean onCameraReady(long sessionId) {
        Cancellable late;
        synchronized (this) {
            if (activeSessionId != sessionId || state != State.PREPARING
                    || runtimeOutstanding || !cameraOutstanding) return false;
            cameraReady = true;
            cameraOutstanding = false;
            externalWorkInFlight++;
            late = cameraHandle;
            cameraHandle = null;
        }
        try {
            safeCancel(late);
        } finally {
            completeExternalWork();
        }
        return true;
    }

    public void onPreviewFrame(long sessionId, FaceFrame ownedFrame) {
        if (ownedFrame == null) return;
        boolean claimed;
        synchronized (this) {
            claimed = activeSessionId == sessionId;
            if (claimed) externalWorkInFlight++;
        }
        if (!claimed) {
            safeClose(ownedFrame);
            return;
        }
        try {
            handlePreviewFrame(sessionId, ownedFrame);
        } finally {
            completeExternalWork();
        }
    }

    private void handlePreviewFrame(long sessionId, FaceFrame ownedFrame) {
        long now;
        try {
            now = clock.elapsedMillis();
        } catch (RuntimeException ignored) {
            boolean terminalClaimed;
            synchronized (this) {
                terminalClaimed = activeSessionId == sessionId
                        && beginTerminationLocked(sessionId, State.FAILURE, null);
            }
            safeClose(ownedFrame);
            if (terminalClaimed) drainTerminalCleanupIfReady();
            return;
        }
        Cancellable cameraTimeout = null;
        boolean enteredDetecting = false;
        boolean alreadyDetecting = false;
        synchronized (this) {
            if (activeSessionId != sessionId) {
                // stale
            } else if (state == State.PREPARING && !runtimeOutstanding && cameraReady) {
                state = State.DETECTING;
                detectionStarting = true;
                enteredDetecting = true;
                cameraTimeout = disarmLocked(cameraTimer);
            } else if (state != State.DETECTING) {
                // not accepted
            } else {
                alreadyDetecting = true;
            }
        }
        if (alreadyDetecting) {
            submitOrQueue(sessionId, ownedFrame, now);
            return;
        }
        if (!enteredDetecting) {
            safeClose(ownedFrame);
            return;
        }
        boolean pumpAfterStart = false;
        try {
            boolean timerArmed = armTimer(detectionTimer, TimerKind.DETECTION, sessionId,
                    State.DETECTING, DETECTION_TIMEOUT_MS);
            safeCancel(cameraTimeout);
            if (!timerArmed) {
                safeClose(ownedFrame);
                return;
            }
            notifyState(sessionId, State.DETECTING);
            submitOrQueue(sessionId, ownedFrame, now);
        } finally {
            synchronized (this) {
                if (activeSessionId == sessionId && state == State.DETECTING
                        && detectionStarting) {
                    detectionStarting = false;
                    pumpAfterStart = true;
                }
            }
        }
        if (pumpAfterStart) pump(sessionId);
    }

    public void onObservation(long sessionId, long frameId, FaceObservation observation) {
        FaceFrame frame;
        Cancellable action;
        synchronized (this) {
            if (activeSessionId != sessionId || state != State.DETECTING
                    || !analysisInFlight || inFlightFrame == null
                    || inFlightFrame.frameId() != frameId) return;
            frame = inFlightFrame;
            inFlightFrame = null;
            analysisInFlight = false;
            analysisCompleting = true;
            externalWorkInFlight++;
            action = analysisHandle;
            analysisHandle = null;
        }
        safeCancel(action);
        try {
            FaceFrameQualityGate.Decision decision = qualityGate.evaluate(observation);
            boolean current;
            synchronized (this) {
                current = activeSessionId == sessionId && state == State.DETECTING
                        && analysisCompleting;
            }
            if (!current) {
                safeClose(frame);
                return;
            }
            notifyDecision(sessionId, decision);
            synchronized (this) {
                current = activeSessionId == sessionId && state == State.DETECTING
                        && analysisCompleting;
            }
            if (!current) {
                safeClose(frame);
            } else if (decision.shouldCapture()) {
                beginCapture(sessionId, frame);
            } else {
                synchronized (this) {
                    if (activeSessionId == sessionId && state == State.DETECTING) {
                        analysisCompleting = false;
                    }
                }
                safeClose(frame);
                pump(sessionId);
            }
        } catch (RuntimeException ignored) {
            safeClose(frame);
            fail(sessionId);
        } finally {
            completeExternalWork();
        }
    }

    public void onJpegReady(long sessionId, long frameId, byte[] ownedJpeg) {
        FaceCapture current;
        synchronized (this) {
            if (activeSessionId != sessionId || state != State.CAPTURING
                    || capture == null || capture.frameId() != frameId || jpegProcessing) {
                current = null;
            } else {
                jpegProcessing = true;
                current = capture;
                externalWorkInFlight++;
            }
        }
        if (current == null) {
            zero(ownedJpeg);
            return;
        }
        try {
        boolean accepted;
        try {
            accepted = current.acceptOwnedJpeg(ownedJpeg);
        } catch (RuntimeException ignored) {
            zero(ownedJpeg);
            accepted = false;
        }
        if (!accepted) {
            boolean terminalClaimed;
            synchronized (this) {
                boolean matches = activeSessionId == sessionId
                        && state == State.CAPTURING && capture == current && jpegProcessing;
                terminalClaimed = matches
                        && beginTerminationLocked(sessionId, State.FAILURE, null);
            }
            if (terminalClaimed) drainTerminalCleanupIfReady();
            return;
        }
        FaceCapture.JpegBorrow borrow;
        try {
            borrow = current.borrowJpeg();
        } catch (RuntimeException ignored) {
            boolean terminalClaimed;
            synchronized (this) {
                boolean matches = activeSessionId == sessionId
                        && state == State.CAPTURING && capture == current && jpegProcessing;
                terminalClaimed = matches
                        && beginTerminationLocked(sessionId, State.FAILURE, null);
            }
            if (terminalClaimed) drainTerminalCleanupIfReady();
            return;
        }
        String requestId = current.requestId();
        Cancellable encodeAction;
        Cancellable encodeTimeout;
        boolean claimed;
        synchronized (this) {
            claimed = activeSessionId == sessionId && state == State.CAPTURING
                    && capture == current && jpegProcessing;
            if (claimed) {
                capture = null;
                jpegProcessing = false;
                state = State.VERIFYING;
                verificationRequestId = requestId;
                verificationBorrow = borrow;
                encodeAction = encodeHandle;
                encodeHandle = null;
                encodeTimeout = disarmLocked(encodeTimer);
            } else {
                encodeAction = null;
                encodeTimeout = null;
            }
        }
        if (!claimed) {
            safeClose(borrow);
            safeClose(current);
            return;
        }
        boolean timerArmed = armTimer(verifyTimer, TimerKind.VERIFY, sessionId,
                State.VERIFYING, verificationTimeoutMillis);
        safeCancel(encodeAction);
        safeCancel(encodeTimeout);
        safeClose(current);
        if (!timerArmed) {
            safeClose(borrow);
            return;
        }
        notifyState(sessionId, State.VERIFYING);
        boolean invokeVerify;
        synchronized (this) {
            invokeVerify = activeSessionId == sessionId && state == State.VERIFYING
                    && requestId.equals(verificationRequestId);
            if (invokeVerify) externalWorkInFlight++;
        }
        if (!invokeVerify) {
            safeClose(borrow);
            return;
        }
        try {
            Cancellable returned = actions.verify(sessionId, requestId, borrow);
            clearVerificationBorrow(sessionId, borrow);
            if (returned == null) {
                safeClose(borrow);
                failIfActionOutstanding(sessionId, FailureStage.VERIFICATION);
            } else {
                installActionHandle(sessionId, returned, FailureStage.VERIFICATION);
            }
        } catch (RuntimeException ignored) {
            clearVerificationBorrow(sessionId, borrow);
            safeClose(borrow);
            failIfActionOutstanding(sessionId, FailureStage.VERIFICATION);
        } finally {
            completeExternalWork();
        }
        } finally {
            completeExternalWork();
        }
    }

    public void onVerification(long sessionId, FaceVerificationResult result) {
        boolean claimed;
        synchronized (this) {
            if (activeSessionId != sessionId || state != State.VERIFYING) return;
            if (result != null && !verificationRequestId.equals(result.requestId())) return;
            State terminal = result != null && result.isPassed()
                    ? State.SUCCESS : State.FAILURE;
            claimed = beginTerminationLocked(sessionId, terminal, result);
        }
        if (claimed) drainTerminalCleanupIfReady();
    }

    public void onFailure(long sessionId, FailureStage stage) {
        if (stage == null) return;
        boolean claimed;
        synchronized (this) {
            boolean matches = activeSessionId == sessionId && (
                    (stage == FailureStage.RUNTIME && state == State.PREPARING && runtimeOutstanding)
                    || (stage == FailureStage.CAMERA && state == State.PREPARING
                            && !runtimeOutstanding)
                    || (stage == FailureStage.ANALYSIS && state == State.DETECTING)
                    || (stage == FailureStage.JPEG && state == State.CAPTURING)
                    || (stage == FailureStage.VERIFICATION && state == State.VERIFYING));
            claimed = matches && beginTerminationLocked(sessionId, State.FAILURE, null);
        }
        if (claimed) drainTerminalCleanupIfReady();
    }

    /**
     * Atomically claims an asynchronous frame failure only while that exact frame still owns the
     * expected analysis or JPEG stage. The hook runs after terminal ownership is claimed and
     * before terminal cleanup can notify listeners.
     */
    boolean failFrameIfCurrent(long sessionId, long frameId, FailureStage stage,
            Runnable onClaimed) {
        if (onClaimed == null) {
            throw new IllegalArgumentException("frame failure hook cannot be null");
        }
        boolean claimed;
        synchronized (this) {
            boolean matches = activeSessionId == sessionId && frameId > 0L
                    && frameFailureMatchesLocked(frameId, stage);
            claimed = matches && beginTerminationLocked(sessionId, State.FAILURE, null);
            if (claimed) externalWorkInFlight++;
        }
        if (!claimed) return false;
        try {
            onClaimed.run();
        } catch (RuntimeException ignored) {
            // A diagnostic hook cannot interrupt authoritative terminal cleanup.
        } finally {
            completeExternalWork();
        }
        return true;
    }

    public void cancel() {
        long sid;
        synchronized (this) {
            sid = activeSessionId;
        }
        if (sid != 0L) terminate(sid, State.CANCELLED, null);
    }

    public synchronized State state() { return state; }
    public synchronized long activeSessionId() { return activeSessionId; }

    private void submitOrQueue(long sid, FaceFrame frame, long now) {
        FaceFrame analyze = null;
        LatestFaceFrameMailbox target = null;
        LatestFaceFrameMailbox obsoleteMailbox = null;
        boolean directCleanupClaimed = false;
        boolean invalidId = false;
        Cancellable oldPump = null;
        synchronized (this) {
            if (activeSessionId != sid || state != State.DETECTING) {
                invalidId = true;
            } else if (frame.frameId() <= highestFrameId) {
                invalidId = true;
            } else {
                highestFrameId = frame.frameId();
                if (!detectionStarting && !analysisInFlight
                        && !analysisCompleting && !pumpClaimed
                        && (lastAnalysisStart == Long.MIN_VALUE
                        || (now >= lastAnalysisStart
                        && now - lastAnalysisStart >= ANALYSIS_INTERVAL_MS))) {
                    analysisInFlight = true;
                    inFlightFrame = frame;
                    analyze = frame;
                    obsoleteMailbox = mailbox;
                    mailbox = new LatestFaceFrameMailbox();
                    pumpSignalPending = false;
                    pumpTimerScheduling = false;
                    pumpScheduleGeneration++;
                    externalWorkInFlight++;
                    directCleanupClaimed = true;
                    oldPump = disarmLocked(pumpTimer);
                } else {
                    target = mailbox;
                }
            }
        }
        safeCancel(oldPump);
        if (directCleanupClaimed) {
            safeClose(obsoleteMailbox);
            completeExternalWork();
        }
        if (invalidId) {
            safeClose(frame);
        } else if (analyze != null) {
            startAnalysis(sid, analyze, now);
        } else {
            if (target == null || !target.offer(frame)) return;
            boolean signal;
            synchronized (this) {
                signal = activeSessionId == sid && state == State.DETECTING
                        && mailbox == target;
                if (signal) pumpSignalPending = true;
            }
            if (signal) pump(sid);
        }
    }

    private void startAnalysis(long sid, FaceFrame frame, long eligibilitySample) {
        boolean invoke;
        synchronized (this) {
            invoke = activeSessionId == sid && state == State.DETECTING
                    && analysisInFlight && inFlightFrame == frame;
            if (invoke) externalWorkInFlight++;
        }
        if (!invoke) {
            safeClose(frame);
            return;
        }
        FaceFrame.Borrow borrow;
        try {
            borrow = frame.borrow();
        } catch (RuntimeException ignored) {
            fail(sid);
            completeExternalWork();
            return;
        }
        try {
            long actualStart = clock.elapsedMillis();
            boolean current;
            boolean rollback;
            boolean rollbackClaimed = false;
            synchronized (this) {
                current = activeSessionId == sid && state == State.DETECTING
                        && analysisInFlight && inFlightFrame == frame;
                rollback = current && (actualStart < eligibilitySample
                        || (lastAnalysisStart != Long.MIN_VALUE
                        && (actualStart < lastAnalysisStart
                        || actualStart - lastAnalysisStart < ANALYSIS_INTERVAL_MS)));
                if (rollback) {
                    rollbackClaimed = beginTerminationLocked(sid, State.FAILURE, null);
                }
                if (current && !rollback) lastAnalysisStart = actualStart;
            }
            if (!current || rollback) {
                safeClose(borrow);
                if (rollbackClaimed) drainTerminalCleanupIfReady();
                return;
            }
            Cancellable returned = actions.analyze(sid, frame.frameId(), borrow);
            if (returned == null) {
                safeClose(borrow);
                failIfAnalysisOutstanding(sid, frame);
            } else {
                installAnalysisHandle(sid, frame, returned);
            }
        } catch (RuntimeException ignored) {
            safeClose(borrow);
            failIfAnalysisOutstanding(sid, frame);
        } finally {
            completeExternalWork();
        }
    }

    private void pump(long sid) { pump(sid, false); }

    private void pump(long sid, boolean redriveAlreadyUsed) {
        synchronized (this) {
            if (activeSessionId != sid || state != State.DETECTING
                    || detectionStarting || analysisInFlight
                    || analysisCompleting || pumpClaimed) return;
            pumpClaimed = true;
            externalWorkInFlight++;
        }
        long now;
        try {
            now = clock.elapsedMillis();
        } catch (RuntimeException ignored) {
            boolean terminalClaimed;
            synchronized (this) {
                boolean matches = activeSessionId == sid && state == State.DETECTING
                        && pumpClaimed;
                terminalClaimed = matches
                        && beginTerminationLocked(sid, State.FAILURE, null);
            }
            completeExternalWork();
            if (terminalClaimed) drainTerminalCleanupIfReady();
            return;
        }
        LatestFaceFrameMailbox source;
        long delay = 0L;
        boolean schedule = false;
        boolean rollbackClaimed = false;
        long scheduleReservation = 0L;
        synchronized (this) {
            if (activeSessionId != sid || state != State.DETECTING || !pumpClaimed) {
                source = null;
            } else if (lastAnalysisStart != Long.MIN_VALUE && now < lastAnalysisStart) {
                rollbackClaimed = beginTerminationLocked(sid, State.FAILURE, null);
                source = null;
            } else if (lastAnalysisStart != Long.MIN_VALUE
                    && now - lastAnalysisStart < ANALYSIS_INTERVAL_MS) {
                pumpClaimed = false;
                source = null;
                if (pumpSignalPending && !pumpTimer.armed && !pumpTimerScheduling) {
                    pumpTimerScheduling = true;
                    scheduleReservation = ++pumpScheduleGeneration;
                    schedule = true;
                    delay = ANALYSIS_INTERVAL_MS - (now - lastAnalysisStart);
                }
            } else {
                pumpSignalPending = false;
                source = mailbox;
            }
        }
        if (rollbackClaimed) {
            completeExternalWork();
            return;
        }
        if (schedule) {
            try {
                armPumpTimer(sid, delay, scheduleReservation);
                boolean redrive = false;
                boolean synchronousFailureClaimed = false;
                synchronized (this) {
                    if (pumpScheduleGeneration == scheduleReservation) {
                        pumpTimerScheduling = false;
                        boolean stillPending = activeSessionId == sid
                                && state == State.DETECTING && pumpSignalPending
                                && !pumpTimer.armed && !analysisInFlight
                                && !analysisCompleting && !pumpClaimed;
                        if (stillPending) {
                            if (redriveAlreadyUsed) {
                                synchronousFailureClaimed = beginTerminationLocked(
                                        sid, State.FAILURE, null);
                            }
                            else redrive = true;
                        }
                    }
                }
                if (synchronousFailureClaimed) drainTerminalCleanupIfReady();
                else if (redrive) pump(sid, true);
            } finally {
                completeExternalWork();
            }
            return;
        }
        if (source == null) {
            completeExternalWork();
            return;
        }
        try {
            FaceFrame frame = source.take();
            boolean accepted;
            boolean retry;
            synchronized (this) {
                accepted = frame != null && activeSessionId == sid
                        && state == State.DETECTING && !analysisInFlight && pumpClaimed;
                pumpClaimed = false;
                retry = frame == null && pumpSignalPending && activeSessionId == sid
                        && state == State.DETECTING && !analysisInFlight
                        && !analysisCompleting;
                if (accepted) {
                    analysisInFlight = true;
                    inFlightFrame = frame;
                }
            }
            if (frame == null) {
                if (retry) pump(sid);
                return;
            }
            if (!accepted) safeClose(frame);
            else startAnalysis(sid, frame, now);
        } finally {
            completeExternalWork();
        }
    }

    private void beginCapture(long sid, FaceFrame frame) {
        FaceCapture newCapture;
        try {
            newCapture = new FaceCapture(sid, frame);
        } catch (RuntimeException ignored) {
            safeClose(frame);
            fail(sid);
            return;
        }
        LatestFaceFrameMailbox oldMailbox;
        List<Cancellable> cancel = new ArrayList<>();
        boolean accepted;
        synchronized (this) {
            accepted = activeSessionId == sid && state == State.DETECTING
                    && analysisCompleting && capture == null;
            if (accepted) {
                analysisCompleting = false;
                capture = newCapture;
                state = State.CAPTURING;
                oldMailbox = mailbox;
                mailbox = null;
                add(cancel, disarmLocked(detectionTimer));
                add(cancel, disarmLocked(pumpTimer));
            } else {
                oldMailbox = null;
            }
        }
        if (!accepted) {
            safeClose(newCapture);
            return;
        }
        boolean timerArmed = armTimer(encodeTimer, TimerKind.ENCODE, sid,
                State.CAPTURING, ENCODE_TIMEOUT_MS);
        cancelAll(cancel);
        safeClose(oldMailbox);
        if (!timerArmed) return;
        notifyState(sid, State.CAPTURING);
        startEncode(sid, newCapture);
    }

    private void startEncode(long sid, FaceCapture current) {
        synchronized (this) {
            if (activeSessionId != sid || state != State.CAPTURING || capture != current) return;
            externalWorkInFlight++;
        }
        FaceFrame.Borrow borrow;
        try {
            borrow = current.borrowFrame();
        } catch (RuntimeException ignored) {
            fail(sid);
            completeExternalWork();
            return;
        }
        try {
            Cancellable returned = actions.encode(sid, current.frameId(), borrow);
            if (returned == null) {
                safeClose(borrow);
                failIfActionOutstanding(sid, FailureStage.JPEG);
            } else {
                installActionHandle(sid, returned, FailureStage.JPEG);
            }
        } catch (RuntimeException ignored) {
            safeClose(borrow);
            failIfActionOutstanding(sid, FailureStage.JPEG);
        } finally {
            completeExternalWork();
        }
    }

    private void installActionHandle(long sid, Cancellable returned, FailureStage stage) {
        boolean installed = false;
        synchronized (this) {
            if (activeSessionId == sid) {
                if (stage == FailureStage.RUNTIME && state == State.PREPARING && runtimeOutstanding) {
                    runtimeHandle = returned; installed = true;
                } else if (stage == FailureStage.CAMERA && state == State.PREPARING && cameraOutstanding) {
                    cameraHandle = returned; installed = true;
                } else if (stage == FailureStage.ANALYSIS && state == State.DETECTING && analysisInFlight) {
                    analysisHandle = returned; installed = true;
                } else if (stage == FailureStage.JPEG && state == State.CAPTURING && capture != null) {
                    encodeHandle = returned; installed = true;
                } else if (stage == FailureStage.VERIFICATION && state == State.VERIFYING) {
                    verifyHandle = returned; installed = true;
                }
            }
        }
        if (!installed) safeCancel(returned);
    }

    private void installAnalysisHandle(long sid, FaceFrame frame, Cancellable returned) {
        boolean installed;
        synchronized (this) {
            installed = activeSessionId == sid && state == State.DETECTING
                    && analysisInFlight && inFlightFrame == frame;
            if (installed) analysisHandle = returned;
        }
        if (!installed) safeCancel(returned);
    }

    private boolean armTimer(TimerSlot slot, TimerKind kind, long sid, State expected,
            long delayMillis) {
        final long generation;
        synchronized (this) {
            if (activeSessionId != sid || state != expected) return false;
            slot.generation++;
            generation = slot.generation;
            slot.armed = true;
            slot.expectedState = expected;
            slot.handle = null;
        }
        return scheduleArmedTimer(slot, kind, sid, expected, delayMillis, generation);
    }

    private boolean armPumpTimer(long sid, long delayMillis, long reservation) {
        final long generation;
        synchronized (this) {
            if (activeSessionId != sid || state != State.DETECTING
                    || !pumpTimerScheduling || pumpScheduleGeneration != reservation) {
                return false;
            }
            pumpTimer.generation++;
            generation = pumpTimer.generation;
            pumpTimer.armed = true;
            pumpTimer.expectedState = State.DETECTING;
            pumpTimer.handle = null;
        }
        return scheduleArmedTimer(pumpTimer, TimerKind.PUMP, sid, State.DETECTING,
                delayMillis, generation);
    }

    private boolean scheduleArmedTimer(TimerSlot slot, TimerKind kind, long sid,
            State expected, long delayMillis, long generation) {
        Cancellable returned;
        try {
            returned = scheduler.schedule(() -> onTimer(slot, kind, sid, generation, expected),
                    delayMillis);
        } catch (RuntimeException ignored) {
            returned = null;
        }
        if (returned == null) {
            boolean terminalClaimed;
            synchronized (this) {
                boolean owned = activeSessionId == sid && state == expected
                        && slot.armed && slot.generation == generation;
                if (owned) {
                    slot.armed = false;
                    slot.generation++;
                }
                terminalClaimed = owned
                        && beginTerminationLocked(sid, State.FAILURE, null);
            }
            if (terminalClaimed) drainTerminalCleanupIfReady();
            return false;
        }
        boolean installed;
        synchronized (this) {
            installed = activeSessionId == sid && state == expected
                    && slot.armed && slot.generation == generation;
            if (installed) slot.handle = returned;
        }
        if (!installed) safeCancel(returned);
        return installed;
    }

    private void onTimer(TimerSlot slot, TimerKind kind, long sid, long generation,
            State expected) {
        boolean pumpDue = false;
        boolean terminalClaimed = false;
        synchronized (this) {
            if (activeSessionId != sid || state != expected || !slot.armed
                    || slot.generation != generation) return;
            slot.armed = false;
            slot.handle = null;
            slot.generation++;
            if (kind == TimerKind.PUMP) pumpDue = true;
            else terminalClaimed = beginTerminationLocked(sid, State.TIMEOUT, null);
        }
        if (pumpDue) pump(sid);
        else if (terminalClaimed) {
            try {
                runLinearizationProbe(LinearizationProbe.Event.TIMER_EXPIRY);
            } finally {
                drainTerminalCleanupIfReady();
            }
        }
    }

    private void fail(long sid) { terminate(sid, State.FAILURE, null); }

    private void terminate(long sid, State terminal, FaceVerificationResult result) {
        boolean claimed;
        synchronized (this) {
            claimed = beginTerminationLocked(sid, terminal, result);
        }
        if (claimed) drainTerminalCleanupIfReady();
    }

    private boolean beginTerminationLocked(long sid, State terminal,
            FaceVerificationResult result) {
        if (activeSessionId != sid || isTerminal(state) || terminating) return false;
        state = terminal;
        activeSessionId = 0L;
        terminating = true;
        Cleanup cleanup = new Cleanup(sid, terminal, result);
        add(cleanup.cancellables, runtimeHandle); runtimeHandle = null;
        add(cleanup.cancellables, cameraHandle); cameraHandle = null;
        add(cleanup.cancellables, analysisHandle); analysisHandle = null;
        add(cleanup.cancellables, encodeHandle); encodeHandle = null;
        add(cleanup.cancellables, verifyHandle); verifyHandle = null;
        add(cleanup.cancellables, disarmLocked(runtimeTimer));
        add(cleanup.cancellables, disarmLocked(cameraTimer));
        add(cleanup.cancellables, disarmLocked(detectionTimer));
        add(cleanup.cancellables, disarmLocked(encodeTimer));
        add(cleanup.cancellables, disarmLocked(verifyTimer));
        add(cleanup.cancellables, disarmLocked(pumpTimer));
        cleanup.mailbox = mailbox; mailbox = null;
        cleanup.inFlight = inFlightFrame; inFlightFrame = null;
        cleanup.capture = capture; capture = null;
        cleanup.jpegBorrow = verificationBorrow; verificationBorrow = null;
        runtimeOutstanding = false;
        cameraOutstanding = false;
        cameraReady = false;
        detectionStarting = false;
        analysisInFlight = false;
        analysisCompleting = false;
        pumpClaimed = false;
        pumpSignalPending = false;
        pumpTimerScheduling = false;
        pumpScheduleGeneration++;
        jpegProcessing = false;
        verificationRequestId = null;
        pendingCleanup = cleanup;
        return true;
    }

    private void completeExternalWork() {
        synchronized (this) {
            if (externalWorkInFlight <= 0) {
                throw new IllegalStateException("external work underflow");
            }
            externalWorkInFlight--;
        }
        drainTerminalCleanupIfReady();
    }

    private void drainTerminalCleanupIfReady() {
        Cleanup cleanup;
        synchronized (this) {
            if (!terminating || externalWorkInFlight != 0 || pendingCleanup == null) return;
            cleanup = pendingCleanup;
            pendingCleanup = null;
        }
        runTerminalCleanup(cleanup);
    }

    private void runTerminalCleanup(Cleanup cleanup) {
        long sid = cleanup.sessionId;
        cancelAll(cleanup.cancellables);
        try {
            actions.stopCamera(sid);
        } catch (RuntimeException ignored) {
            // Continue deterministic resource cleanup.
        }
        safeClose(cleanup.mailbox);
        safeClose(cleanup.inFlight);
        safeClose(cleanup.capture);
        safeClose(cleanup.jpegBorrow);
        notifyState(sid, cleanup.terminal);
        try {
            listener.onTerminal(sid, cleanup.terminal, cleanup.result);
        } catch (RuntimeException ignored) {
            // Terminal cleanup already completed.
        } finally {
            synchronized (this) {
                terminating = false;
            }
        }
    }

    private Cancellable disarmLocked(TimerSlot slot) {
        if (!slot.armed && slot.handle == null) return null;
        Cancellable handle = slot.handle;
        slot.handle = null;
        slot.armed = false;
        slot.generation++;
        return handle;
    }

    private void clearVerificationBorrow(long sid, FaceCapture.JpegBorrow borrow) {
        synchronized (this) {
            if (verificationBorrow == borrow) verificationBorrow = null;
        }
    }

    private void failIfStageCurrent(long sid, FailureStage stage) {
        boolean claimed;
        synchronized (this) {
            claimed = stageMatchesLocked(sid, stage)
                    && beginTerminationLocked(sid, State.FAILURE, null);
        }
        if (claimed) drainTerminalCleanupIfReady();
    }

    private void failIfActionOutstanding(long sid, FailureStage stage) {
        boolean claimed;
        synchronized (this) {
            boolean current;
            if (stage == FailureStage.CAMERA) {
                current = activeSessionId == sid && state == State.PREPARING
                        && cameraOutstanding;
            } else {
                current = stageMatchesLocked(sid, stage);
            }
            claimed = current && beginTerminationLocked(sid, State.FAILURE, null);
        }
        if (claimed) {
            try {
                runLinearizationProbe(LinearizationProbe.Event.ACTION_FAILURE);
            } finally {
                drainTerminalCleanupIfReady();
            }
        }
    }

    private void failIfAnalysisOutstanding(long sid, FaceFrame frame) {
        boolean claimed;
        synchronized (this) {
            claimed = activeSessionId == sid && state == State.DETECTING
                    && analysisInFlight && inFlightFrame == frame
                    && beginTerminationLocked(sid, State.FAILURE, null);
        }
        if (claimed) {
            try {
                runLinearizationProbe(LinearizationProbe.Event.ACTION_FAILURE);
            } finally {
                drainTerminalCleanupIfReady();
            }
        }
    }

    private void runLinearizationProbe(LinearizationProbe.Event event) {
        if (linearizationProbe != null) linearizationProbe.afterTerminalClaim(event);
    }

    private boolean stageMatchesLocked(long sid, FailureStage stage) {
        return activeSessionId == sid && (
                (stage == FailureStage.RUNTIME && state == State.PREPARING && runtimeOutstanding)
                || (stage == FailureStage.CAMERA && state == State.PREPARING && !runtimeOutstanding)
                || (stage == FailureStage.ANALYSIS && state == State.DETECTING && analysisInFlight)
                || (stage == FailureStage.JPEG && state == State.CAPTURING)
                || (stage == FailureStage.VERIFICATION && state == State.VERIFYING));
    }

    private boolean frameFailureMatchesLocked(long frameId, FailureStage stage) {
        if (stage == FailureStage.ANALYSIS) {
            return state == State.DETECTING && analysisInFlight && !analysisCompleting
                    && inFlightFrame != null && inFlightFrame.frameId() == frameId;
        }
        if (stage == FailureStage.JPEG) {
            return state == State.CAPTURING && !jpegProcessing
                    && capture != null && capture.frameId() == frameId;
        }
        return false;
    }

    private long nextPositiveSessionIdLocked() {
        if (nextSessionId == Long.MAX_VALUE) return 0L;
        nextSessionId++;
        if (nextSessionId <= 0L) return 0L;
        return nextSessionId;
    }

    private void notifyState(long sid, State next) {
        synchronized (notificationGate) {
            synchronized (this) {
                if (state != next) return;
                if (!isTerminal(next) && activeSessionId != sid) return;
            }
            try { listener.onStateChanged(sid, next); } catch (RuntimeException ignored) { }
        }
    }

    private void notifyDecision(long sid, FaceFrameQualityGate.Decision decision) {
        synchronized (notificationGate) {
            synchronized (this) {
                if (activeSessionId != sid || state != State.DETECTING
                        || !analysisCompleting) return;
            }
            try { listener.onQualityDecision(sid, decision); } catch (RuntimeException ignored) { }
        }
    }

    private static boolean isTerminal(State value) {
        return value == State.SUCCESS || value == State.FAILURE
                || value == State.CANCELLED || value == State.TIMEOUT;
    }

    private static void add(List<Cancellable> values, Cancellable value) {
        if (value != null) values.add(value);
    }

    private static void cancelAll(List<Cancellable> values) {
        for (Cancellable value : values) safeCancel(value);
    }

    private static void safeCancel(Cancellable value) {
        if (value == null) return;
        try { value.cancel(); } catch (RuntimeException ignored) { }
    }

    private static void safeClose(FaceFrame value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    private static void safeClose(FaceFrame.Borrow value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    private static void safeClose(FaceCapture value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    private static void safeClose(FaceCapture.JpegBorrow value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    private static void safeClose(LatestFaceFrameMailbox value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
