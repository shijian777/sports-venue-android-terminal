package com.codex.lockertest.face;

import com.codex.lockertest.face.verification.FaceVerificationClient;
import com.codex.lockertest.face.verification.FaceVerificationRequest;
import com.codex.lockertest.face.verification.FaceVerificationResult;

/**
 * Pure-Java owner of one customer face-recognition attempt.
 *
 * <p>The controller deliberately knows nothing about Android or the Baidu SDK. Android camera,
 * permission and vendor-runtime details are supplied through the narrow ports below, which keeps
 * lifecycle cancellation and fail-closed behavior testable on the JVM.</p>
 */
public final class FaceRecognitionController implements AutoCloseable {
    public static final long ONLINE_VERIFICATION_TIMEOUT_MILLIS = 35_000L;

    public enum FailureReason {
        BINDING_UNAVAILABLE,
        LICENSE_FAILED,
        LICENSE_INVALID,
        RUNTIME_FAILED,
        PERMISSION_DENIED,
        PERMISSION_PERMANENTLY_DENIED,
        CAMERA_FAILED,
        ANALYSIS_FAILED,
        JPEG_FAILED,
        VERIFICATION_FAILED,
        PREPARE_TIMEOUT,
        CAMERA_TIMEOUT,
        DETECTION_TIMEOUT,
        JPEG_TIMEOUT,
        VERIFICATION_TIMEOUT,
        CANCELLED
    }

    public enum DiagnosticStage {
        BINDING, LICENSE, RUNTIME, PERMISSION, CAMERA,
        ANALYSIS, JPEG, VERIFICATION, SESSION
    }

    /** Stable authorization classification; vendor text/codes never enter the UI. */
    public enum LicenseFailure { INVALID, UNAVAILABLE }

    public interface EpochClock {
        long epochMillis();
    }

    public interface LicensePort {
        interface Callback {
            void onReady(long sessionId);
            void onFailure(long sessionId, String diagnosticCode);

            /** Backward-compatible structured path for license-manager adapters. */
            default void onFailure(long sessionId, LicenseFailure failure,
                    String diagnosticCode) {
                onFailure(sessionId, failure == LicenseFailure.INVALID
                        ? "LICENSE_INVALID" : "LICENSE_UNAVAILABLE");
            }
        }

        FaceCaptureSession.Cancellable checkLocal(long sessionId, Callback callback);
    }

    public interface RuntimePort {
        interface InitCallback {
            void onReady(long sessionId);
            void onFailure(long sessionId, String diagnosticCode);
        }

        interface AnalysisCallback {
            void onCompleted(long sessionId, long frameId, Analysis analysis);
            void onFailure(long sessionId, long frameId, String diagnosticCode);
        }

        FaceCaptureSession.Cancellable initialize(long sessionId, InitCallback callback);

        FaceCaptureSession.Cancellable analyze(long sessionId, long frameId,
                FaceFrame.Borrow ownedFrame, AnalysisCallback callback);
    }

    public interface PermissionPort {
        interface Callback {
            void onResult(long sessionId, boolean granted, boolean permanentlyDenied);
        }

        boolean isGranted();

        FaceCaptureSession.Cancellable requestOnce(long sessionId, Callback callback);
    }

    public interface CameraPort extends AutoCloseable {
        interface Callback {
            void onReady(long sessionId);
            void onFrame(long sessionId, FaceFrame ownedFrame);
            void onError(long sessionId, String diagnosticCode);
        }

        FaceCaptureSession.Cancellable start(long sessionId, Callback callback);
        void stop(long sessionId);
        @Override void close();
    }

    public interface BindingPort {
        boolean isAvailable();
        String deviceBinding();
        String processBinding();
    }

    public interface DiagnosticSink {
        void record(long sessionId, DiagnosticStage stage, String boundedCode);
    }

    public interface Listener {
        void onStateChanged(long sessionId, FaceCaptureSession.State state);
        void onQualityDecision(long sessionId, QualityEvent event);
        void onTerminal(long sessionId, TerminalOutcome outcome);
    }

    public static final class PreviewBox {
        private final int frameWidth;
        private final int frameHeight;
        private final float centerX;
        private final float centerY;
        private final float width;
        private final float height;

        public PreviewBox(int frameWidth, int frameHeight, float centerX, float centerY,
                float width, float height) {
            if (frameWidth <= 0 || frameHeight <= 0 || width < 0f || height < 0f) {
                throw new IllegalArgumentException("preview geometry is malformed");
            }
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }

        public int frameWidth() { return frameWidth; }
        public int frameHeight() { return frameHeight; }
        public float centerX() { return centerX; }
        public float centerY() { return centerY; }
        public float width() { return width; }
        public float height() { return height; }
    }

    public static final class Analysis {
        private final FaceObservation observation;
        private final PreviewBox previewBox;

        public Analysis(FaceObservation observation, PreviewBox previewBox) {
            if (observation == null) {
                throw new IllegalArgumentException("observation cannot be null");
            }
            this.observation = observation;
            this.previewBox = previewBox;
        }

        public FaceObservation observation() { return observation; }
        public PreviewBox previewBox() { return previewBox; }
    }

    public static final class QualityEvent {
        private final FaceFrameQualityGate.Decision decision;
        private final PreviewBox previewBox;

        private QualityEvent(FaceFrameQualityGate.Decision decision, PreviewBox previewBox) {
            if (decision == null) throw new IllegalArgumentException("decision cannot be null");
            this.decision = decision;
            this.previewBox = previewBox;
        }

        public FaceFrameQualityGate.Decision decision() { return decision; }
        public FaceFrameQualityGate.Outcome outcome() { return decision.outcome(); }
        public String customerHint() { return decision.customerHint(); }
        public PreviewBox previewBox() { return previewBox; }
    }

    public static final class Success {
        private final FaceVerificationResult result;
        private final String expectedRequestId;
        private final String expectedDeviceBinding;
        private final String expectedProcessBinding;

        private Success(FaceVerificationResult result, String expectedRequestId,
                String expectedDeviceBinding, String expectedProcessBinding) {
            if (result == null || expectedRequestId == null
                    || expectedDeviceBinding == null || expectedProcessBinding == null) {
                throw new IllegalArgumentException("success context cannot be null");
            }
            this.result = result;
            this.expectedRequestId = expectedRequestId;
            this.expectedDeviceBinding = expectedDeviceBinding;
            this.expectedProcessBinding = expectedProcessBinding;
        }

        public FaceVerificationResult result() { return result; }
        public String expectedRequestId() { return expectedRequestId; }
        public String expectedDeviceBinding() { return expectedDeviceBinding; }
        public String expectedProcessBinding() { return expectedProcessBinding; }
    }

    public static final class TerminalOutcome {
        private final Success success;
        private final FailureReason failureReason;
        private final boolean retryable;
        private final String safeMessage;

        private TerminalOutcome(Success success, FailureReason failureReason,
                boolean retryable, String safeMessage) {
            this.success = success;
            this.failureReason = failureReason;
            this.retryable = retryable;
            this.safeMessage = safeMessage;
        }

        private static TerminalOutcome passed(Success success) {
            return new TerminalOutcome(success, null, false, "识别成功");
        }

        private static TerminalOutcome failed(FailureReason reason) {
            return new TerminalOutcome(null, reason, isRetryable(reason), messageFor(reason));
        }

        public boolean isSuccess() { return success != null; }
        public Success success() { return success; }
        public FailureReason failureReason() { return failureReason; }
        public boolean retryable() { return retryable; }
        public String safeMessage() { return safeMessage; }
    }

    private enum Phase {
        IDLE, LICENSE, RUNTIME, PERMISSION, CAMERA,
        DETECTING, CAPTURING, VERIFYING
    }

    private enum PreparationStep {
        LICENSE, RUNTIME, PERMISSION, DONE, FAILED, CANCELLED
    }

    private static final FaceCaptureSession.Cancellable NO_OP_CANCELLABLE =
            new FaceCaptureSession.Cancellable() {
                @Override public void cancel() { }
            };

    private final Object gate = new Object();
    private final EpochClock epochClock;
    private final LicensePort license;
    private final RuntimePort runtime;
    private final PermissionPort permission;
    private final CameraPort camera;
    private final FaceJpegEncoder encoder;
    private final FaceVerificationClient verifier;
    private final BindingPort bindings;
    private final DiagnosticSink diagnostics;
    private final Listener listener;
    private final FaceCaptureSession session;

    private boolean closed;
    private long contextSessionId;
    private Phase phase = Phase.IDLE;
    private FailureReason pendingFailure;
    private String expectedRequestId;
    private String expectedDeviceBinding;
    private String expectedProcessBinding;
    private long previewSessionId;
    private PreviewBox decisionPreview;

    public FaceRecognitionController(FaceCaptureSession.Clock elapsedClock,
            EpochClock epochClock,
            FaceCaptureSession.Scheduler scheduler,
            LicensePort license,
            RuntimePort runtime,
            PermissionPort permission,
            CameraPort camera,
            FaceJpegEncoder encoder,
            FaceVerificationClient verifier,
            BindingPort bindings,
            FaceFrameQualityGate qualityGate,
            DiagnosticSink diagnostics,
            Listener listener) {
        this(elapsedClock, epochClock, scheduler, license, runtime, permission,
                camera, encoder, verifier, bindings, qualityGate, diagnostics,
                listener, FaceCaptureSession.DEFAULT_VERIFICATION_TIMEOUT_MILLIS);
    }

    public FaceRecognitionController(FaceCaptureSession.Clock elapsedClock,
            EpochClock epochClock,
            FaceCaptureSession.Scheduler scheduler,
            LicensePort license,
            RuntimePort runtime,
            PermissionPort permission,
            CameraPort camera,
            FaceJpegEncoder encoder,
            FaceVerificationClient verifier,
            BindingPort bindings,
            FaceFrameQualityGate qualityGate,
            DiagnosticSink diagnostics,
            Listener listener,
            long verificationTimeoutMillis) {
        if (elapsedClock == null || epochClock == null || scheduler == null
                || license == null || runtime == null || permission == null
                || camera == null || encoder == null || verifier == null
                || bindings == null || qualityGate == null || diagnostics == null
                || listener == null) {
            throw new IllegalArgumentException("controller dependencies cannot be null");
        }
        this.epochClock = epochClock;
        this.license = license;
        this.runtime = runtime;
        this.permission = permission;
        this.camera = camera;
        this.encoder = encoder;
        this.verifier = verifier;
        this.bindings = bindings;
        this.diagnostics = diagnostics;
        this.listener = listener;
        this.session = new FaceCaptureSession(
                elapsedClock, scheduler, new SessionActions(), qualityGate,
                new SessionListener(), verificationTimeoutMillis);
    }

    public long start() {
        synchronized (gate) {
            if (closed) return 0L;
        }
        return session.start();
    }

    public long retry() {
        synchronized (gate) {
            if (closed) return 0L;
        }
        session.cancel();
        synchronized (gate) {
            if (closed) return 0L;
        }
        return session.start();
    }

    public void cancel() {
        session.cancel();
    }

    public FaceCaptureSession.State state() {
        return session.state();
    }

    public long activeSessionId() {
        return session.activeSessionId();
    }

    @Override public void close() {
        synchronized (gate) {
            if (closed) return;
            closed = true;
        }
        session.cancel();
        try {
            encoder.close();
        } catch (RuntimeException ignored) {
            record(0L, DiagnosticStage.JPEG, "JPEG_CLOSE_FAILED");
        }
        try {
            camera.close();
        } catch (RuntimeException ignored) {
            record(0L, DiagnosticStage.CAMERA, "CAMERA_CLOSE_FAILED");
        }
    }

    private final class SessionActions implements FaceCaptureSession.Actions {
        @Override public FaceCaptureSession.Cancellable prepareRuntime(long sessionId) {
            PreparationOperation operation = new PreparationOperation(sessionId);
            beginPreparation(sessionId, operation);
            return operation;
        }

        @Override public FaceCaptureSession.Cancellable startCamera(long sessionId) {
            setPhase(sessionId, Phase.CAMERA);
            FaceCaptureSession.Cancellable handle;
            try {
                handle = camera.start(sessionId, new CameraPort.Callback() {
                    @Override public void onReady(long callbackSessionId) {
                        session.onCameraReady(callbackSessionId);
                    }

                    @Override public void onFrame(long callbackSessionId,
                            FaceFrame ownedFrame) {
                        session.onPreviewFrame(callbackSessionId, ownedFrame);
                    }

                    @Override public void onError(long callbackSessionId,
                            String ignoredDiagnosticCode) {
                        FaceCaptureSession.State current = session.state();
                        if (current == FaceCaptureSession.State.DETECTING) {
                            markFailure(callbackSessionId, FailureReason.CAMERA_FAILED,
                                    DiagnosticStage.CAMERA, "CAMERA_STREAM_FAILED");
                            session.onFailure(callbackSessionId,
                                    FaceCaptureSession.FailureStage.ANALYSIS);
                        } else {
                            markFailure(callbackSessionId, FailureReason.CAMERA_FAILED,
                                    DiagnosticStage.CAMERA, "CAMERA_START_FAILED");
                            session.onFailure(callbackSessionId,
                                    FaceCaptureSession.FailureStage.CAMERA);
                        }
                    }
                });
            } catch (RuntimeException ignored) {
                handle = null;
            }
            if (handle == null) {
                markFailure(sessionId, FailureReason.CAMERA_FAILED,
                        DiagnosticStage.CAMERA, "CAMERA_START_FAILED");
            }
            return handle;
        }

        @Override public FaceCaptureSession.Cancellable analyze(long sessionId, long frameId,
                FaceFrame.Borrow borrowedFrame) {
            setPhase(sessionId, Phase.DETECTING);
            FaceCaptureSession.Cancellable handle;
            try {
                handle = runtime.analyze(sessionId, frameId, borrowedFrame,
                        new RuntimePort.AnalysisCallback() {
                            @Override public void onCompleted(long callbackSessionId,
                                    long callbackFrameId, Analysis analysis) {
                                if (analysis == null || analysis.observation() == null) {
                                    failFrameIfCurrent(callbackSessionId, callbackFrameId,
                                            FailureReason.ANALYSIS_FAILED,
                                            DiagnosticStage.ANALYSIS,
                                            "ANALYSIS_RESULT_INVALID",
                                            FaceCaptureSession.FailureStage.ANALYSIS);
                                    return;
                                }
                                setDecisionPreview(callbackSessionId, analysis.previewBox());
                                try {
                                    session.onObservation(callbackSessionId, callbackFrameId,
                                            analysis.observation());
                                } finally {
                                    clearDecisionPreview(callbackSessionId,
                                            analysis.previewBox());
                                }
                            }

                            @Override public void onFailure(long callbackSessionId,
                                    long callbackFrameId, String ignoredDiagnosticCode) {
                                failFrameIfCurrent(callbackSessionId, callbackFrameId,
                                        FailureReason.ANALYSIS_FAILED,
                                        DiagnosticStage.ANALYSIS, "ANALYSIS_FAILED",
                                        FaceCaptureSession.FailureStage.ANALYSIS);
                            }
                        });
            } catch (RuntimeException ignored) {
                handle = null;
            }
            return handle;
        }

        @Override public FaceCaptureSession.Cancellable encode(long sessionId, long frameId,
                FaceFrame.Borrow borrowedFrame) {
            setPhase(sessionId, Phase.CAPTURING);
            FaceCaptureSession.Cancellable handle;
            try {
                handle = encoder.encode(frameId, borrowedFrame, new FaceJpegEncoder.Callback() {
                    @Override public void onEncoded(long callbackFrameId,
                            FaceJpegEncoder.OwnedJpeg ownedJpeg) {
                        byte[] ownedBytes = null;
                        try {
                            if (ownedJpeg != null) ownedBytes = ownedJpeg.take();
                        } catch (RuntimeException ignored) {
                            ownedBytes = null;
                        }
                        if (ownedBytes == null) {
                            failFrameIfCurrent(sessionId, callbackFrameId,
                                    FailureReason.JPEG_FAILED,
                                    DiagnosticStage.JPEG, "JPEG_RESULT_INVALID",
                                    FaceCaptureSession.FailureStage.JPEG);
                        } else {
                            session.onJpegReady(sessionId, callbackFrameId, ownedBytes);
                        }
                    }

                    @Override public void onFailure(long callbackFrameId,
                            String ignoredSafeMessage, String ignoredDiagnosticCode) {
                        failFrameIfCurrent(sessionId, callbackFrameId,
                                FailureReason.JPEG_FAILED,
                                DiagnosticStage.JPEG, "JPEG_FAILED",
                                FaceCaptureSession.FailureStage.JPEG);
                    }
                });
            } catch (RuntimeException ignored) {
                handle = null;
            }
            return handle;
        }

        @Override public FaceCaptureSession.Cancellable verify(long sessionId,
                String requestId, FaceCapture.JpegBorrow borrowedJpeg) {
            setPhase(sessionId, Phase.VERIFYING);
            FaceVerificationRequest createdRequest;
            try {
                String device;
                String process;
                synchronized (gate) {
                    if (contextSessionId != sessionId) {
                        throw new IllegalStateException("stale verification session");
                    }
                    expectedRequestId = copy(requestId);
                    device = expectedDeviceBinding;
                    process = expectedProcessBinding;
                }
                createdRequest = new FaceVerificationRequest(requestId, borrowedJpeg.jpeg(),
                        epochClock.epochMillis(), device, process);
            } catch (RuntimeException ignored) {
                markFailure(sessionId, FailureReason.VERIFICATION_FAILED,
                        DiagnosticStage.VERIFICATION, "VERIFICATION_REQUEST_FAILED");
                safeClose(borrowedJpeg);
                return null;
            }
            safeClose(borrowedJpeg);
            final FaceVerificationRequest request = createdRequest;

            FaceVerificationClient.Cancellable delegate;
            try {
                delegate = verifier.verify(request, new FaceVerificationClient.Callback() {
                    @Override public void onCompleted(FaceVerificationResult result) {
                        safeClose(request);
                        if (result == null || !requestId.equals(result.requestId())) {
                            markFailure(sessionId, FailureReason.VERIFICATION_FAILED,
                                    DiagnosticStage.VERIFICATION,
                                    "VERIFICATION_RESULT_INVALID");
                            session.onFailure(sessionId,
                                    FaceCaptureSession.FailureStage.VERIFICATION);
                            return;
                        }
                        if (!result.isPassed()) {
                            markFailure(sessionId, FailureReason.VERIFICATION_FAILED,
                                    DiagnosticStage.VERIFICATION,
                                    "VERIFICATION_NOT_PASSED");
                        }
                        session.onVerification(sessionId, result);
                    }
                });
            } catch (RuntimeException ignored) {
                safeClose(request);
                delegate = null;
            }
            if (delegate == null) {
                safeClose(request);
                return null;
            }
            request.commitOwnedJpegTransfer();
            final FaceVerificationClient.Cancellable cancellable = delegate;
            return new FaceCaptureSession.Cancellable() {
                @Override public void cancel() {
                    safeClose(request);
                    try {
                        cancellable.cancel();
                    } catch (RuntimeException ignored) {
                        // Cancellation is best-effort; session cleanup must continue.
                    }
                }
            };
        }

        @Override public void stopCamera(long sessionId) {
            try {
                camera.stop(sessionId);
            } catch (RuntimeException ignored) {
                record(sessionId, DiagnosticStage.CAMERA, "CAMERA_STOP_FAILED");
            }
        }
    }

    private final class SessionListener implements FaceCaptureSession.Listener {
        @Override public void onStateChanged(long sessionId,
                FaceCaptureSession.State state) {
            if (state == FaceCaptureSession.State.DETECTING) {
                setPhase(sessionId, Phase.DETECTING);
            } else if (state == FaceCaptureSession.State.CAPTURING) {
                setPhase(sessionId, Phase.CAPTURING);
            } else if (state == FaceCaptureSession.State.VERIFYING) {
                setPhase(sessionId, Phase.VERIFYING);
            }
            try {
                listener.onStateChanged(sessionId, state);
            } catch (RuntimeException ignored) {
                record(sessionId, DiagnosticStage.SESSION, "STATE_LISTENER_FAILED");
            }
        }

        @Override public void onQualityDecision(long sessionId,
                FaceFrameQualityGate.Decision decision) {
            PreviewBox preview;
            synchronized (gate) {
                preview = previewSessionId == sessionId ? decisionPreview : null;
            }
            try {
                listener.onQualityDecision(sessionId, new QualityEvent(decision, preview));
            } catch (RuntimeException ignored) {
                record(sessionId, DiagnosticStage.SESSION, "QUALITY_LISTENER_FAILED");
            }
        }

        @Override public void onTerminal(long sessionId, FaceCaptureSession.State state,
                FaceVerificationResult result) {
            TerminalOutcome outcome = terminalOutcome(sessionId, state, result);
            try {
                listener.onTerminal(sessionId, outcome);
            } catch (RuntimeException ignored) {
                record(sessionId, DiagnosticStage.SESSION, "TERMINAL_LISTENER_FAILED");
            }
        }
    }

    private void beginPreparation(long sessionId, PreparationOperation operation) {
        String device = null;
        String process = null;
        boolean available = false;
        try {
            available = bindings.isAvailable();
            if (available) {
                device = copy(bindings.deviceBinding());
                process = copy(bindings.processBinding());
                available = usableBinding(device) && usableBinding(process);
            }
        } catch (RuntimeException ignored) {
            available = false;
        }
        synchronized (gate) {
            contextSessionId = sessionId;
            phase = Phase.LICENSE;
            pendingFailure = null;
            expectedRequestId = null;
            expectedDeviceBinding = device;
            expectedProcessBinding = process;
            previewSessionId = 0L;
            decisionPreview = null;
        }
        if (!available) {
            failPreparation(operation, PreparationStep.LICENSE,
                    FailureReason.BINDING_UNAVAILABLE,
                    DiagnosticStage.BINDING, "BINDING_UNAVAILABLE");
            return;
        }
        beginLicense(operation);
    }

    private void beginLicense(PreparationOperation operation) {
        FaceCaptureSession.Cancellable returned;
        try {
            returned = license.checkLocal(operation.sessionId, new LicensePort.Callback() {
                @Override public void onReady(long callbackSessionId) {
                    if (callbackSessionId != operation.sessionId
                            || !operation.advance(PreparationStep.LICENSE,
                                    PreparationStep.RUNTIME)) return;
                    setPhase(callbackSessionId, Phase.RUNTIME);
                    beginRuntime(operation);
                }

                @Override public void onFailure(long callbackSessionId,
                        String diagnosticCode) {
                    onFailure(callbackSessionId,
                            "LICENSE_INVALID".equals(diagnosticCode)
                                    ? LicenseFailure.INVALID
                                    : LicenseFailure.UNAVAILABLE,
                            diagnosticCode);
                }

                @Override public void onFailure(long callbackSessionId,
                        LicenseFailure failure, String ignoredDiagnosticCode) {
                    if (callbackSessionId != operation.sessionId) return;
                    failPreparation(operation, PreparationStep.LICENSE,
                            failure == LicenseFailure.INVALID
                                    ? FailureReason.LICENSE_INVALID
                                    : FailureReason.LICENSE_FAILED,
                            DiagnosticStage.LICENSE,
                            failure == LicenseFailure.INVALID
                                    ? "LICENSE_INVALID" : "LICENSE_UNAVAILABLE");
                }
            });
        } catch (RuntimeException ignored) {
            returned = null;
        }
        if (returned == null) {
            failPreparation(operation, PreparationStep.LICENSE,
                    FailureReason.LICENSE_FAILED,
                    DiagnosticStage.LICENSE, "LICENSE_FAILED");
        } else {
            operation.installOrCancel(PreparationStep.LICENSE, returned);
        }
    }

    private void beginRuntime(PreparationOperation operation) {
        FaceCaptureSession.Cancellable returned;
        try {
            returned = runtime.initialize(operation.sessionId, new RuntimePort.InitCallback() {
                @Override public void onReady(long callbackSessionId) {
                    if (callbackSessionId != operation.sessionId) return;
                    boolean granted;
                    try {
                        granted = permission.isGranted();
                    } catch (RuntimeException ignored) {
                        granted = false;
                    }
                    if (granted) {
                        if (operation.advance(PreparationStep.RUNTIME,
                                PreparationStep.DONE)) {
                            session.onRuntimeReady(callbackSessionId);
                        }
                        return;
                    }
                    if (!operation.advance(PreparationStep.RUNTIME,
                            PreparationStep.PERMISSION)) return;
                    setPhase(callbackSessionId, Phase.PERMISSION);
                    beginPermission(operation);
                }

                @Override public void onFailure(long callbackSessionId,
                        String ignoredDiagnosticCode) {
                    if (callbackSessionId != operation.sessionId) return;
                    failPreparation(operation, PreparationStep.RUNTIME,
                            FailureReason.RUNTIME_FAILED,
                            DiagnosticStage.RUNTIME, "RUNTIME_FAILED");
                }
            });
        } catch (RuntimeException ignored) {
            returned = null;
        }
        if (returned == null) {
            failPreparation(operation, PreparationStep.RUNTIME,
                    FailureReason.RUNTIME_FAILED,
                    DiagnosticStage.RUNTIME, "RUNTIME_FAILED");
        } else {
            operation.installOrCancel(PreparationStep.RUNTIME, returned);
        }
    }

    private void beginPermission(PreparationOperation operation) {
        FaceCaptureSession.Cancellable returned;
        try {
            returned = permission.requestOnce(operation.sessionId,
                    new PermissionPort.Callback() {
                        @Override public void onResult(long callbackSessionId,
                                boolean granted, boolean permanentlyDenied) {
                            if (callbackSessionId != operation.sessionId) return;
                            if (granted) {
                                if (operation.advance(PreparationStep.PERMISSION,
                                        PreparationStep.DONE)) {
                                    session.onRuntimeReady(callbackSessionId);
                                }
                            } else {
                                failPreparation(operation, PreparationStep.PERMISSION,
                                        permanentlyDenied
                                                ? FailureReason.PERMISSION_PERMANENTLY_DENIED
                                                : FailureReason.PERMISSION_DENIED,
                                        DiagnosticStage.PERMISSION,
                                        permanentlyDenied
                                                ? "PERMISSION_PERMANENTLY_DENIED"
                                                : "PERMISSION_DENIED");
                            }
                        }
                    });
        } catch (RuntimeException ignored) {
            returned = null;
        }
        if (returned == null) {
            failPreparation(operation, PreparationStep.PERMISSION,
                    FailureReason.PERMISSION_DENIED,
                    DiagnosticStage.PERMISSION, "PERMISSION_REQUEST_FAILED");
        } else {
            operation.installOrCancel(PreparationStep.PERMISSION, returned);
        }
    }

    private void failPreparation(PreparationOperation operation, PreparationStep step,
            FailureReason reason, DiagnosticStage stage, String code) {
        if (!operation.advance(step, PreparationStep.FAILED)) return;
        markFailure(operation.sessionId, reason, stage, code);
        session.onFailure(operation.sessionId, FaceCaptureSession.FailureStage.RUNTIME);
    }

    private TerminalOutcome terminalOutcome(long sessionId, FaceCaptureSession.State state,
            FaceVerificationResult result) {
        FailureReason reason;
        Phase terminalPhase;
        String request;
        String device;
        String process;
        synchronized (gate) {
            terminalPhase = contextSessionId == sessionId ? phase : Phase.IDLE;
            reason = contextSessionId == sessionId ? pendingFailure : null;
            request = contextSessionId == sessionId ? expectedRequestId : null;
            device = contextSessionId == sessionId ? expectedDeviceBinding : null;
            process = contextSessionId == sessionId ? expectedProcessBinding : null;
            if (contextSessionId == sessionId) {
                contextSessionId = 0L;
                phase = Phase.IDLE;
                pendingFailure = null;
                expectedRequestId = null;
                expectedDeviceBinding = null;
                expectedProcessBinding = null;
                previewSessionId = 0L;
                decisionPreview = null;
            }
        }
        if (state == FaceCaptureSession.State.SUCCESS && result != null
                && result.isPassed() && request != null && device != null && process != null) {
            return TerminalOutcome.passed(new Success(
                    result, copy(request), copy(device), copy(process)));
        }
        if (reason == null) {
            if (state == FaceCaptureSession.State.CANCELLED) {
                reason = FailureReason.CANCELLED;
            } else if (state == FaceCaptureSession.State.TIMEOUT) {
                reason = timeoutFor(terminalPhase);
            } else {
                reason = failureFor(terminalPhase);
            }
        }
        if (state == FaceCaptureSession.State.TIMEOUT) {
            record(sessionId, stageFor(reason), reason.name());
        }
        return TerminalOutcome.failed(reason);
    }

    private void markFailure(long sessionId, FailureReason reason,
            DiagnosticStage stage, String fixedCode) {
        boolean accepted;
        synchronized (gate) {
            accepted = contextSessionId == sessionId && pendingFailure == null;
            if (accepted) pendingFailure = reason;
        }
        if (accepted) record(sessionId, stage, fixedCode);
    }

    private void failFrameIfCurrent(long sessionId, long frameId, FailureReason reason,
            DiagnosticStage diagnosticStage, String fixedCode,
            FaceCaptureSession.FailureStage failureStage) {
        session.failFrameIfCurrent(sessionId, frameId, failureStage,
                () -> markFailure(sessionId, reason, diagnosticStage, fixedCode));
    }

    private void setPhase(long sessionId, Phase next) {
        synchronized (gate) {
            if (contextSessionId == sessionId) phase = next;
        }
    }

    private void setDecisionPreview(long sessionId, PreviewBox preview) {
        synchronized (gate) {
            if (contextSessionId != sessionId) return;
            previewSessionId = sessionId;
            decisionPreview = preview;
        }
    }

    private void clearDecisionPreview(long sessionId, PreviewBox preview) {
        synchronized (gate) {
            if (previewSessionId == sessionId && decisionPreview == preview) {
                previewSessionId = 0L;
                decisionPreview = null;
            }
        }
    }

    private void record(long sessionId, DiagnosticStage stage, String fixedCode) {
        String bounded = boundedFixedCode(fixedCode);
        try {
            diagnostics.record(sessionId, stage, bounded);
        } catch (RuntimeException ignored) {
            // Diagnostics must never affect customer flow or resource cleanup.
        }
    }

    private static FailureReason timeoutFor(Phase phase) {
        switch (phase) {
            case CAMERA: return FailureReason.CAMERA_TIMEOUT;
            case DETECTING: return FailureReason.DETECTION_TIMEOUT;
            case CAPTURING: return FailureReason.JPEG_TIMEOUT;
            case VERIFYING: return FailureReason.VERIFICATION_TIMEOUT;
            default: return FailureReason.PREPARE_TIMEOUT;
        }
    }

    private static FailureReason failureFor(Phase phase) {
        switch (phase) {
            case LICENSE: return FailureReason.LICENSE_FAILED;
            case RUNTIME: return FailureReason.RUNTIME_FAILED;
            case PERMISSION: return FailureReason.PERMISSION_DENIED;
            case CAMERA: return FailureReason.CAMERA_FAILED;
            case DETECTING: return FailureReason.ANALYSIS_FAILED;
            case CAPTURING: return FailureReason.JPEG_FAILED;
            case VERIFYING: return FailureReason.VERIFICATION_FAILED;
            default: return FailureReason.RUNTIME_FAILED;
        }
    }

    private static DiagnosticStage stageFor(FailureReason reason) {
        switch (reason) {
            case CAMERA_FAILED:
            case CAMERA_TIMEOUT:
                return DiagnosticStage.CAMERA;
            case ANALYSIS_FAILED:
            case DETECTION_TIMEOUT:
                return DiagnosticStage.ANALYSIS;
            case JPEG_FAILED:
            case JPEG_TIMEOUT:
                return DiagnosticStage.JPEG;
            case VERIFICATION_FAILED:
            case VERIFICATION_TIMEOUT:
                return DiagnosticStage.VERIFICATION;
            case LICENSE_FAILED:
            case LICENSE_INVALID:
                return DiagnosticStage.LICENSE;
            case BINDING_UNAVAILABLE:
                return DiagnosticStage.BINDING;
            case PERMISSION_DENIED:
            case PERMISSION_PERMANENTLY_DENIED:
                return DiagnosticStage.PERMISSION;
            default:
                return DiagnosticStage.RUNTIME;
        }
    }

    private static boolean isRetryable(FailureReason reason) {
        return reason != FailureReason.BINDING_UNAVAILABLE
                && reason != FailureReason.LICENSE_INVALID
                && reason != FailureReason.PERMISSION_PERMANENTLY_DENIED;
    }

    private static String messageFor(FailureReason reason) {
        switch (reason) {
            case BINDING_UNAVAILABLE:
            case LICENSE_INVALID:
                return "人脸功能尚未激活，请联系管理员";
            case LICENSE_FAILED:
            case RUNTIME_FAILED:
            case PREPARE_TIMEOUT:
                return "人脸识别准备失败，请稍后重试";
            case PERMISSION_DENIED:
                return "需要摄像头权限才能进行人脸识别";
            case PERMISSION_PERMANENTLY_DENIED:
                return "摄像头权限已关闭，请联系管理员";
            case CAMERA_FAILED:
            case CAMERA_TIMEOUT:
                return "摄像头启动失败，请重新尝试";
            case ANALYSIS_FAILED:
            case DETECTION_TIMEOUT:
                return "未能完成人脸检测，请重新尝试";
            case JPEG_FAILED:
            case JPEG_TIMEOUT:
                return "人脸照片处理失败，请重新尝试";
            case VERIFICATION_FAILED:
            case VERIFICATION_TIMEOUT:
                return "人脸验证未通过，请重新尝试";
            case CANCELLED:
            default:
                return "人脸识别已取消";
        }
    }

    private static String boundedFixedCode(String fixedCode) {
        if (fixedCode == null || fixedCode.length() == 0) return "UNKNOWN";
        int limit = Math.min(64, fixedCode.length());
        StringBuilder result = new StringBuilder(limit);
        for (int index = 0; index < limit; index++) {
            char value = fixedCode.charAt(index);
            boolean valid = (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '_';
            result.append(valid ? value : '_');
        }
        return result.length() == 0 ? "UNKNOWN" : result.toString();
    }

    private static boolean usableBinding(String value) {
        if (value == null || value.length() == 0 || value.length() > 128) return false;
        char first = value.charAt(0);
        if (!isAsciiLetterOrDigit(first)) return false;
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrDigit(character) && character != '.' && character != '_'
                    && character != ':' && character != '-') return false;
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9');
    }

    private static String copy(String value) {
        return value == null ? null : new String(value);
    }

    private static void safeCancel(FaceCaptureSession.Cancellable cancellable) {
        if (cancellable == null) return;
        try {
            cancellable.cancel();
        } catch (RuntimeException ignored) {
            // Continue deterministic state transition.
        }
    }

    private static void safeClose(FaceCapture.JpegBorrow borrowedJpeg) {
        if (borrowedJpeg == null) return;
        try {
            borrowedJpeg.close();
        } catch (RuntimeException ignored) {
            // Session cleanup remains authoritative.
        }
    }

    private static void safeClose(FaceVerificationRequest request) {
        if (request == null) return;
        try {
            request.close();
        } catch (RuntimeException ignored) {
            // Verification request cleanup is best-effort and idempotent.
        }
    }

    private static final class PreparationOperation
            implements FaceCaptureSession.Cancellable {
        private final long sessionId;
        private PreparationStep step = PreparationStep.LICENSE;
        private FaceCaptureSession.Cancellable handle;

        PreparationOperation(long sessionId) {
            this.sessionId = sessionId;
        }

        boolean advance(PreparationStep expected, PreparationStep next) {
            FaceCaptureSession.Cancellable cancel;
            synchronized (this) {
                if (step != expected) return false;
                step = next;
                cancel = handle;
                handle = null;
            }
            safeCancel(cancel);
            return true;
        }

        void installOrCancel(PreparationStep expected,
                FaceCaptureSession.Cancellable returned) {
            boolean installed;
            synchronized (this) {
                installed = step == expected && handle == null;
                if (installed) handle = returned;
            }
            if (!installed) safeCancel(returned);
        }

        @Override public void cancel() {
            FaceCaptureSession.Cancellable cancel;
            synchronized (this) {
                if (step == PreparationStep.CANCELLED) return;
                step = PreparationStep.CANCELLED;
                cancel = handle;
                handle = null;
            }
            safeCancel(cancel);
        }
    }
}
