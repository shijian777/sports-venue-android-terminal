package com.codex.lockertest.face;

import android.view.SurfaceHolder;

import java.util.Arrays;

/** Distinct local-only detection/capture pipeline; it has no verification or upload client. */
public final class FaceEnrollmentCaptureController implements AutoCloseable {
    public interface Listener {
        void onCameraReady(long sessionId);
        void onStateChanged(long sessionId, FaceCaptureSession.State state);
        void onQualityDecision(long sessionId, FaceFrameQualityGate.Decision decision);
        /** Takes ownership of ownedJpeg only when returning true. */
        boolean onCaptureCompleted(long sessionId, byte[] ownedJpeg);
        void onFailure(long sessionId, String safeMessage, boolean retryable);
    }

    private static final FaceCaptureSession.Cancellable NO_OP = () -> { };
    private static final String SAFE_FAILURE = "人脸采集失败，请重新尝试";

    private final BaiduFaceRuntime runtime;
    private final SurfaceHolder previewHolder;
    private final int displayRotationDegrees;
    private final FaceCaptureSession.Scheduler scheduler;
    private final Listener listener;
    private final Camera1FaceCameraController camera =
            new Camera1FaceCameraController();
    private final AndroidFaceJpegEncoder encoder = new AndroidFaceJpegEncoder();
    private final FaceCaptureSession session;
    private volatile boolean captureCompleted;

    public FaceEnrollmentCaptureController(
            BaiduFaceRuntime runtime,
            SurfaceHolder previewHolder,
            int displayRotationDegrees,
            FaceCaptureSession.Scheduler scheduler,
            FaceFrameQualityGate qualityGate,
            Listener listener) {
        if (runtime == null || previewHolder == null || scheduler == null
                || qualityGate == null || listener == null) {
            throw new IllegalArgumentException("enrollment capture dependencies are required");
        }
        this.runtime = runtime;
        this.previewHolder = previewHolder;
        this.displayRotationDegrees = displayRotationDegrees;
        this.scheduler = scheduler;
        this.listener = listener;
        this.session = new FaceCaptureSession(
                elapsedClock(), scheduler, new SessionActions(), qualityGate,
                new SessionListener());
    }

    public long start() {
        captureCompleted = false;
        return session.start();
    }

    public long activeSessionId() {
        return session.activeSessionId();
    }

    public void cancel() {
        session.cancel();
    }

    @Override public void close() {
        try {
            session.cancel();
        } finally {
            try {
                camera.close();
            } finally {
                encoder.close();
            }
        }
    }

    private FaceCaptureSession.Clock elapsedClock() {
        return () -> android.os.SystemClock.elapsedRealtime();
    }

    private final class SessionActions implements FaceCaptureSession.Actions {
        @Override public FaceCaptureSession.Cancellable prepareRuntime(long sessionId) {
            FaceCaptureSession.Cancellable handle = scheduler.schedule(
                    () -> session.onRuntimeReady(sessionId), 0L);
            // Preserve FaceCaptureSession's fail-closed contract: a rejected
            // scheduler returns null and must terminate before opening camera.
            return handle;
        }

        @Override public FaceCaptureSession.Cancellable startCamera(long sessionId) {
            return camera.start(previewHolder, displayRotationDegrees,
                    new Camera1FaceCameraController.Listener() {
                        @Override public void onCameraReady(
                                Camera1FaceCameraController.CameraDescriptor descriptor) {
                            if (session.onCameraReady(sessionId)) {
                                if (session.activeSessionId() == sessionId
                                        && session.state()
                                        == FaceCaptureSession.State.PREPARING) {
                                    safelyCameraReady(sessionId);
                                }
                            }
                        }

                        @Override public void onPreviewFrame(FaceFrame ownedFrame) {
                            session.onPreviewFrame(sessionId, ownedFrame);
                        }

                        @Override public void onCameraError(
                                String safeMessage, String diagnosticCode) {
                            session.onFailure(sessionId,
                                    FaceCaptureSession.FailureStage.CAMERA);
                        }
                    });
        }

        @Override public FaceCaptureSession.Cancellable analyze(
                long sessionId, long frameId, FaceFrame.Borrow borrowedFrame) {
            return runtime.analyze(frameId, borrowedFrame,
                    new BaiduFaceRuntime.AnalysisCallback() {
                        @Override public void onCompleted(
                                long requestId, BaiduFaceRuntime.Analysis analysis) {
                            session.onObservation(sessionId, requestId,
                                    analysis.observation());
                        }

                        @Override public void onFailure(
                                long requestId, int safeCode, String safeMessage) {
                            session.onFailure(sessionId,
                                    FaceCaptureSession.FailureStage.ANALYSIS);
                        }
                    });
        }

        @Override public FaceCaptureSession.Cancellable encode(
                long sessionId, long frameId, FaceFrame.Borrow borrowedFrame) {
            return encoder.encode(frameId, borrowedFrame,
                    new FaceJpegEncoder.Callback() {
                        @Override public void onEncoded(
                                long encodedFrameId, FaceJpegEncoder.OwnedJpeg ownedJpeg) {
                            byte[] bytes = null;
                            try {
                                bytes = ownedJpeg.take();
                                session.onJpegReady(sessionId, encodedFrameId, bytes);
                                bytes = null;
                            } catch (RuntimeException | LinkageError ignored) {
                                zero(bytes);
                                session.onFailure(sessionId,
                                        FaceCaptureSession.FailureStage.JPEG);
                            }
                        }

                        @Override public void onFailure(long encodedFrameId,
                                String safeMessage, String diagnosticCode) {
                            session.onFailure(sessionId,
                                    FaceCaptureSession.FailureStage.JPEG);
                        }
                    });
        }

        @Override public FaceCaptureSession.Cancellable verify(long sessionId,
                String requestId, FaceCapture.JpegBorrow borrowedJpeg) {
            byte[] demoCopy = null;
            boolean accepted = false;
            try {
                byte[] source = borrowedJpeg.jpeg();
                demoCopy = Arrays.copyOf(source, source.length);
                accepted = listener.onCaptureCompleted(sessionId, demoCopy);
                if (accepted) {
                    demoCopy = null;
                    captureCompleted = true;
                }
            } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
                accepted = false;
            } finally {
                zero(demoCopy);
                try { borrowedJpeg.close(); }
                catch (RuntimeException | LinkageError ignored) { }
            }
            session.cancel();
            if (!accepted) safelyFailure(sessionId, SAFE_FAILURE, true);
            return NO_OP;
        }

        @Override public void stopCamera(long sessionId) {
            camera.stop();
        }
    }

    private final class SessionListener implements FaceCaptureSession.Listener {
        @Override public void onStateChanged(
                long sessionId, FaceCaptureSession.State state) {
            try { listener.onStateChanged(sessionId, state); }
            catch (RuntimeException | LinkageError ignored) { }
        }

        @Override public void onQualityDecision(long sessionId,
                FaceFrameQualityGate.Decision decision) {
            try { listener.onQualityDecision(sessionId, decision); }
            catch (RuntimeException | LinkageError ignored) { }
        }

        @Override public void onTerminal(long sessionId,
                FaceCaptureSession.State state,
                com.codex.lockertest.face.verification.FaceVerificationResult result) {
            if (!captureCompleted && state != FaceCaptureSession.State.CANCELLED) {
                safelyFailure(sessionId, SAFE_FAILURE, true);
            }
        }
    }

    private void safelyCameraReady(long sessionId) {
        try { listener.onCameraReady(sessionId); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private void safelyFailure(long sessionId, String message, boolean retryable) {
        try { listener.onFailure(sessionId, message, retryable); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
