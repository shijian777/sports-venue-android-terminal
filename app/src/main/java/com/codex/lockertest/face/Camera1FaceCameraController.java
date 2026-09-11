package com.codex.lockertest.face;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

public final class Camera1FaceCameraController implements AutoCloseable {
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final int CALLBACK_BUFFER_COUNT = 3;
    private static final int BUFFER_BYTES = 460800;

    private static final String SAFE_CAMERA_ERROR = "摄像头启动失败，请重新尝试";
    private static final String CAMERA_BUSY = "CAMERA_BUSY";
    private static final String CAMERA_CONTROLLER_CLOSED = "CAMERA_CONTROLLER_CLOSED";
    private static final String CAMERA_OPEN_FAILED = "CAMERA_OPEN_FAILED";
    private static final String CAMERA_CONFIGURATION_FAILED = "CAMERA_CONFIGURATION_FAILED";
    private static final String CAMERA_PREVIEW_FAILED = "CAMERA_PREVIEW_FAILED";
    private static final String SURFACE_DESTROYED = "SURFACE_DESTROYED";

    private static final FaceCaptureSession.Cancellable COMPLETED_HANDLE =
            new FaceCaptureSession.Cancellable() {
                @Override public void cancel() { }
            };

    public static final class CameraDescriptor {
        private final int cameraId;
        private final int sensorOrientation;
        private final int width;
        private final int height;
        private final int fpsMin;
        private final int fpsMax;
        private final CameraOrientationPolicy.Transform transform;

        private CameraDescriptor(int cameraId, int sensorOrientation,
                int width, int height, int fpsMin, int fpsMax,
                CameraOrientationPolicy.Transform transform) {
            this.cameraId = cameraId;
            this.sensorOrientation = sensorOrientation;
            this.width = width;
            this.height = height;
            this.fpsMin = fpsMin;
            this.fpsMax = fpsMax;
            this.transform = transform;
        }

        public int cameraId() { return cameraId; }
        public int sensorOrientation() { return sensorOrientation; }
        public int width() { return width; }
        public int height() { return height; }
        public int fpsMin() { return fpsMin; }
        public int fpsMax() { return fpsMax; }
        public CameraOrientationPolicy.Transform transform() { return transform; }
    }

    public interface Listener {
        void onCameraReady(CameraDescriptor descriptor);
        void onPreviewFrame(FaceFrame ownedFrame);
        void onCameraError(String safeMessage, String diagnosticCode);
    }

    private static final class BufferLease {
        boolean leased;
    }

    private final class GenerationSurfaceCallback implements SurfaceHolder.Callback {
        private final long generation;
        private final SurfaceHolder expectedHolder;
        private final int displayRotationDegrees;

        GenerationSurfaceCallback(long generation, SurfaceHolder expectedHolder,
                int displayRotationDegrees) {
            this.generation = generation;
            this.expectedHolder = expectedHolder;
            this.displayRotationDegrees = displayRotationDegrees;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (holder == expectedHolder) requestOpenOnce(generation, holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (holder == expectedHolder) requestOpenOnce(generation, holder);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (holder == expectedHolder) failAttempt(generation, SURFACE_DESTROYED);
        }
    }

    private static final class AttemptRecord {
        final long generation;
        final SurfaceHolder holder;
        final SurfaceHolder.Callback surfaceCallback;
        final CountDownLatch surfaceRegistrationComplete = new CountDownLatch(1);
        final CountDownLatch cleanupComplete = new CountDownLatch(1);
        Thread registrationThread;
        boolean callbackAdded;
        boolean removalPending;
        boolean openRequested;
        boolean failureClaimed;
        boolean cleanupExecutionClaimed;
        CameraDescriptor descriptor;

        AttemptRecord(long generation, SurfaceHolder holder,
                SurfaceHolder.Callback surfaceCallback) {
            this.generation = generation;
            this.holder = holder;
            this.surfaceCallback = surfaceCallback;
        }
    }

    private static final class CameraConfiguration {
        final CameraDescriptor descriptor;

        CameraConfiguration(CameraDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }

    private static final class OpenOutcome {
        String diagnosticCode;
        boolean cancelled;
    }

    private static final class PreviewDispatch {
        final FaceFrame frame;
        final Listener listener;
        final CameraDescriptor descriptor;
        final boolean first;
        final String diagnosticCode;

        private PreviewDispatch(FaceFrame frame, Listener listener,
                CameraDescriptor descriptor, boolean first, String diagnosticCode) {
            this.frame = frame;
            this.listener = listener;
            this.descriptor = descriptor;
            this.first = first;
            this.diagnosticCode = diagnosticCode;
        }

        static PreviewDispatch frame(FaceFrame frame, Listener listener,
                CameraDescriptor descriptor, boolean first) {
            return new PreviewDispatch(frame, listener, descriptor, first, null);
        }

        static PreviewDispatch failure(String diagnosticCode) {
            return new PreviewDispatch(null, null, null, false, diagnosticCode);
        }
    }

    private static final class AttemptEndedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final ReentrantLock stateLock = new ReentrantLock();
    private final CameraHalGate halOwnershipGate = new CameraHalGate();
    private final CameraAttemptStateMachine machine = new CameraAttemptStateMachine();
    private final HandlerThread ownerThread;
    private final Handler ownerHandler;

    private AttemptRecord activeAttempt;
    private Listener activeListener;
    private boolean closeRequested;
    private boolean emergencyClaimed;
    private long frameId;

    /* Every field below is read or changed only while halOwnershipGate is held. */
    private Camera activeCamera;
    private long activeCameraGeneration;
    private IdentityHashMap<byte[], BufferLease> bufferRegistry;
    private long uncertainCameraGeneration;

    public Camera1FaceCameraController() {
        ownerThread = new HandlerThread("locker-face-camera1-owner");
        ownerThread.start();
        ownerHandler = new Handler(ownerThread.getLooper());
    }

    public FaceCaptureSession.Cancellable start(
            SurfaceHolder holder,
            int displayRotationDegrees,
            Listener listener) {
        if (holder == null || listener == null) {
            throw new IllegalArgumentException("camera start dependencies cannot be null");
        }
        CameraOrientationPolicy.frontCamera(0, displayRotationDegrees);

        final long generation;
        final AttemptRecord attempt;
        String rejectedCode = null;
        stateLock.lock();
        try {
            if (machine.state() == CameraAttemptStateMachine.State.CLOSED
                    || closeRequested) {
                rejectedCode = CAMERA_CONTROLLER_CLOSED;
                generation = 0L;
                attempt = null;
            } else {
                generation = machine.beginStart();
                if (generation == 0L) {
                    rejectedCode = machine.state() == CameraAttemptStateMachine.State.CLOSED
                            ? CAMERA_CONTROLLER_CLOSED : CAMERA_BUSY;
                    attempt = null;
                } else {
                    GenerationSurfaceCallback surfaceCallback =
                            new GenerationSurfaceCallback(generation, holder,
                                    displayRotationDegrees);
                    attempt = new AttemptRecord(generation, holder, surfaceCallback);
                    activeAttempt = attempt;
                    activeListener = listener;
                    emergencyClaimed = false;
                }
            }
        } finally {
            stateLock.unlock();
        }

        if (rejectedCode != null) {
            notifyErrorOnce(listener, rejectedCode);
            return completedHandle();
        }

        boolean callbackAdded = false;
        boolean registrationFailed = false;
        boolean removeAfterRegistration = false;
        SurfaceHolder.Callback surfaceCallback = attempt.surfaceCallback;
        stateLock.lock();
        try {
            attempt.registrationThread = Thread.currentThread();
        } finally {
            stateLock.unlock();
        }
        try {
            holder.addCallback(surfaceCallback);
            callbackAdded = true;
        } catch (RuntimeException | LinkageError ignored) {
            registrationFailed = true;
        } finally {
            stateLock.lock();
            try {
                attempt.callbackAdded = callbackAdded;
                attempt.registrationThread = null;
                removeAfterRegistration = attempt.removalPending;
            } finally {
                stateLock.unlock();
                attempt.surfaceRegistrationComplete.countDown();
            }
        }
        if (removeAfterRegistration) removeSurfaceCallbackDirect(attempt);

        if (registrationFailed) {
            failAttempt(generation, CAMERA_CONFIGURATION_FAILED);
            return new StartupHandle(generation);
        }

        try {
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                requestOpenOnce(generation, holder);
            }
        } catch (RuntimeException | LinkageError ignored) {
            failAttempt(generation, CAMERA_CONFIGURATION_FAILED);
        }
        return new StartupHandle(generation);
    }

    public void stop() {
        stopNoThrow(false);
    }

    public boolean isRunning() {
        stateLock.lock();
        try {
            return machine.isRunning();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void close() {
        stopNoThrow(true);
        try {
            ownerThread.quitSafely();
        } catch (RuntimeException | LinkageError ignored) {
            // The attempt cleanup has already reached its terminal barrier.
        }
        if (!isOwnerThread()) joinOwnerUninterruptibly();
    }

    private final class StartupHandle implements FaceCaptureSession.Cancellable {
        private final long generation;

        StartupHandle(long generation) {
            this.generation = generation;
        }

        @Override
        public void cancel() {
            cancelStartupNoThrow(generation);
        }
    }

    private void cancelStartupNoThrow(long generation) {
        try {
            AttemptRecord attempt = null;
            stateLock.lock();
            try {
                if (activeAttempt != null && activeAttempt.generation == generation) {
                    if (machine.cancelStartup(generation)) {
                        activeListener = null;
                        attempt = activeAttempt;
                    } else if (machine.state() == CameraAttemptStateMachine.State.STOPPING) {
                        attempt = activeAttempt;
                    }
                }
            } finally {
                stateLock.unlock();
            }
            if (attempt != null) executeCleanupAndWait(attempt);
        } catch (RuntimeException | LinkageError ignored) {
            forceClosedCleanupNoThrow(generation);
        }
    }

    private void stopNoThrow(boolean permanent) {
        try {
            AttemptRecord attempt = null;
            stateLock.lock();
            try {
                if (permanent) closeRequested = true;
                CameraAttemptStateMachine.State state = machine.state();
                if (state == CameraAttemptStateMachine.State.IDLE) {
                    if (permanent) machine.close();
                } else if (state == CameraAttemptStateMachine.State.STOPPING) {
                    attempt = activeAttempt;
                } else if (state != CameraAttemptStateMachine.State.CLOSED) {
                    if (machine.beginStop()) {
                        activeListener = null;
                        attempt = activeAttempt;
                    }
                } else {
                    attempt = activeAttempt;
                }
            } finally {
                stateLock.unlock();
            }
            if (attempt != null) executeCleanupAndWait(attempt);
        } catch (RuntimeException | LinkageError ignored) {
            forceClosedCleanupNoThrow(0L);
        }
    }

    private void forceClosedCleanupNoThrow(long expectedGeneration) {
        AttemptRecord attempt = null;
        try {
            stateLock.lock();
            try {
                closeRequested = true;
                if (activeAttempt != null && (expectedGeneration == 0L
                        || activeAttempt.generation == expectedGeneration)) {
                    machine.beginStop();
                    activeListener = null;
                    attempt = activeAttempt;
                } else if (activeAttempt == null) {
                    machine.close();
                }
            } finally {
                stateLock.unlock();
            }
            if (attempt != null) cleanupAfterPostRejected(attempt);
        } catch (RuntimeException | LinkageError ignored) {
            stateLock.lock();
            try {
                machine.close();
            } finally {
                stateLock.unlock();
            }
        }
    }

    private void requestOpenOnce(long generation, SurfaceHolder holder) {
        AttemptRecord attempt;
        stateLock.lock();
        try {
            attempt = activeAttempt;
            if (attempt == null || attempt.generation != generation
                    || attempt.holder != holder || emergencyClaimed
                    || machine.state() != CameraAttemptStateMachine.State.STARTING
                    || attempt.openRequested) return;
            attempt.openRequested = true;
        } finally {
            stateLock.unlock();
        }
        if (!postOwner(new Runnable() {
            @Override public void run() { openCameraOnOwner(generation, holder); }
        })) {
            failAttempt(generation, CAMERA_OPEN_FAILED);
        }
    }

    private void openCameraOnOwner(final long generation, final SurfaceHolder holder) {
        final OpenOutcome outcome = new OpenOutcome();
        try {
            boolean admitted = halOwnershipGate.ownerCall(generation, new Runnable() {
                @Override public void run() {
                    if (!ownerAttemptCurrent(generation)) {
                        outcome.cancelled = true;
                        return;
                    }

                    int cameraId = -1;
                    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                    try {
                        requireOwnerCurrent(generation);
                        int count = Camera.getNumberOfCameras();
                        cameraId = CameraSelectionPolicy.selectCameraId(count,
                                new CameraSelectionPolicy.FacingReader() {
                                    @Override public boolean isFrontFacing(int id) {
                                        requireOwnerCurrent(generation);
                                        Camera.getCameraInfo(id, cameraInfo);
                                        return cameraInfo.facing
                                                == Camera.CameraInfo.CAMERA_FACING_FRONT;
                                    }
                                });
                    } catch (AttemptEndedException ended) {
                        outcome.cancelled = true;
                        return;
                    } catch (SecurityException ignored) {
                        outcome.diagnosticCode = CAMERA_OPEN_FAILED;
                        return;
                    } catch (RuntimeException | LinkageError ignored) {
                        outcome.diagnosticCode = CAMERA_OPEN_FAILED;
                        return;
                    }
                    if (cameraId < 0) {
                        outcome.diagnosticCode = CAMERA_OPEN_FAILED;
                        return;
                    }

                    Camera camera;
                    try {
                        requireOwnerCurrent(generation);
                        camera = Camera.open(cameraId);
                    } catch (AttemptEndedException ended) {
                        outcome.cancelled = true;
                        return;
                    } catch (SecurityException ignored) {
                        outcome.diagnosticCode = CAMERA_OPEN_FAILED;
                        return;
                    } catch (RuntimeException | LinkageError ignored) {
                        outcome.diagnosticCode = CAMERA_OPEN_FAILED;
                        return;
                    }

                    boolean publishCamera;
                    stateLock.lock();
                    try {
                        CameraAttemptStateMachine.State state = machine.state();
                        publishCamera = activeAttempt != null
                                && activeAttempt.generation == generation
                                && !emergencyClaimed
                                && state == CameraAttemptStateMachine.State.STARTING;
                        if (publishCamera) {
                            activeCamera = camera;
                            activeCameraGeneration = generation;
                        }
                    } finally {
                        stateLock.unlock();
                    }
                    if (!publishCamera) {
                        boolean released = releaseCamera(camera);
                        if (!released) uncertainCameraGeneration = generation;
                        outcome.cancelled = true;
                        return;
                    }
                    final CameraConfiguration configuration;
                    try {
                        configuration = configureCamera(camera, cameraId,
                                cameraInfo.orientation, generation, holder);
                    } catch (AttemptEndedException ended) {
                        outcome.cancelled = true;
                        return;
                    } catch (IOException ignored) {
                        outcome.diagnosticCode = CAMERA_CONFIGURATION_FAILED;
                        return;
                    } catch (SecurityException ignored) {
                        outcome.diagnosticCode = CAMERA_CONFIGURATION_FAILED;
                        return;
                    } catch (RuntimeException | LinkageError ignored) {
                        outcome.diagnosticCode = CAMERA_CONFIGURATION_FAILED;
                        return;
                    } catch (OutOfMemoryError ignored) {
                        outcome.diagnosticCode = CAMERA_CONFIGURATION_FAILED;
                        return;
                    }

                    try {
                        requireOwnerCurrent(generation);
                        camera.startPreview();
                    } catch (AttemptEndedException ended) {
                        outcome.cancelled = true;
                        return;
                    } catch (SecurityException ignored) {
                        outcome.diagnosticCode = CAMERA_PREVIEW_FAILED;
                        return;
                    } catch (RuntimeException | LinkageError ignored) {
                        outcome.diagnosticCode = CAMERA_PREVIEW_FAILED;
                        return;
                    }

                    stateLock.lock();
                    try {
                        AttemptRecord attempt = activeAttempt;
                        if (attempt == null || attempt.generation != generation
                                || emergencyClaimed
                                || !machine.markPreviewActive(generation)) {
                            outcome.cancelled = true;
                        } else {
                            attempt.descriptor = configuration.descriptor;
                        }
                    } finally {
                        stateLock.unlock();
                    }
                }
            });
            if (!admitted) outcome.cancelled = true;
        } catch (RuntimeException | LinkageError ignored) {
            outcome.diagnosticCode = CAMERA_OPEN_FAILED;
        }
        if (outcome.diagnosticCode != null) {
            failAttempt(generation, outcome.diagnosticCode);
        }
    }

    private CameraConfiguration configureCamera(Camera camera, int cameraId,
            int sensorOrientation, long generation, SurfaceHolder holder) throws IOException {
        requireOwnerCurrent(generation);
        CameraOrientationPolicy.Transform transform =
                CameraOrientationPolicy.frontCamera(sensorOrientation,
                        displayRotationFor(generation));

        requireOwnerCurrent(generation);
        Camera.Parameters parameters = camera.getParameters();
        boolean exactSize = false;
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        if (sizes != null) {
            for (Camera.Size size : sizes) {
                if (size != null && size.width == PREVIEW_WIDTH
                        && size.height == PREVIEW_HEIGHT) {
                    exactSize = true;
                    break;
                }
            }
        }
        if (!exactSize) throw new IllegalStateException("required preview size unavailable");

        boolean nv21 = false;
        List<Integer> formats = parameters.getSupportedPreviewFormats();
        if (formats != null) {
            for (Integer format : formats) {
                if (format != null && format.intValue() == ImageFormat.NV21) {
                    nv21 = true;
                    break;
                }
            }
        }
        if (!nv21) throw new IllegalStateException("required preview format unavailable");

        List<int[]> supportedRanges = parameters.getSupportedPreviewFpsRange();
        int[][] copiedRanges = new int[supportedRanges == null ? 0 : supportedRanges.size()][];
        if (supportedRanges != null) {
            for (int i = 0; i < supportedRanges.size(); i++) {
                int[] range = supportedRanges.get(i);
                copiedRanges[i] = range == null ? null : range.clone();
            }
        }
        int[] selectedFps = CameraOrientationPolicy.selectFpsRange(copiedRanges);

        parameters.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        parameters.setPreviewFormat(ImageFormat.NV21);
        parameters.setPreviewFpsRange(selectedFps[0], selectedFps[1]);
        requireOwnerCurrent(generation);
        camera.setParameters(parameters);
        requireOwnerCurrent(generation);
        Camera.Parameters readback = camera.getParameters();
        verifyReadback(readback, selectedFps);

        requireOwnerCurrent(generation);
        camera.setDisplayOrientation(transform.previewDisplayOrientation());
        requireOwnerCurrent(generation);
        camera.setPreviewDisplay(holder);
        requireOwnerCurrent(generation);
        camera.setErrorCallback(new Camera.ErrorCallback() {
            @Override public void onError(int error, Camera callbackCamera) {
                onCameraHalError(generation, callbackCamera);
            }
        });
        requireOwnerCurrent(generation);
        camera.setPreviewCallbackWithBuffer((buffer, callbackCamera) ->
                onPreviewBuffer(buffer, callbackCamera, generation));
        allocateCallbackBuffers(camera, generation);

        return new CameraConfiguration(new CameraDescriptor(cameraId, sensorOrientation,
                PREVIEW_WIDTH, PREVIEW_HEIGHT, selectedFps[0], selectedFps[1], transform));
    }

    private void verifyReadback(Camera.Parameters readback, int[] selectedFps) {
        if (readback == null) throw new IllegalStateException("missing camera readback");
        Camera.Size size = readback.getPreviewSize();
        if (size == null || size.width != PREVIEW_WIDTH || size.height != PREVIEW_HEIGHT) {
            throw new IllegalStateException("preview size changed by camera");
        }
        if (readback.getPreviewFormat() != ImageFormat.NV21) {
            throw new IllegalStateException("preview format changed by camera");
        }
        int[] actualFps = new int[2];
        readback.getPreviewFpsRange(actualFps);
        if (actualFps[0] != selectedFps[0] || actualFps[1] != selectedFps[1]) {
            throw new IllegalStateException("preview FPS changed by camera");
        }
    }

    private void allocateCallbackBuffers(Camera camera, long generation) {
        long pixels = (long) PREVIEW_WIDTH * (long) PREVIEW_HEIGHT;
        long expected = pixels + pixels / 2L;
        if (expected != BUFFER_BYTES) {
            throw new IllegalStateException("preview buffer arithmetic failed");
        }
        IdentityHashMap<byte[], BufferLease> allocated =
                new IdentityHashMap<byte[], BufferLease>();
        stateLock.lock();
        try {
            CameraAttemptStateMachine.State state = machine.state();
            if (activeAttempt == null || activeAttempt.generation != generation
                    || emergencyClaimed
                    || state != CameraAttemptStateMachine.State.STARTING) {
                throw new AttemptEndedException();
            }
            bufferRegistry = allocated;
        } finally {
            stateLock.unlock();
        }
        for (int i = 0; i < CALLBACK_BUFFER_COUNT; i++) {
            byte[] buffer = new byte[BUFFER_BYTES];
            stateLock.lock();
            try {
                CameraAttemptStateMachine.State state = machine.state();
                if (activeAttempt == null || activeAttempt.generation != generation
                        || emergencyClaimed
                        || state != CameraAttemptStateMachine.State.STARTING) {
                    throw new AttemptEndedException();
                }
                allocated.put(buffer, new BufferLease());
                camera.addCallbackBuffer(buffer);
            } finally {
                stateLock.unlock();
            }
        }
    }

    private void onCameraHalError(final long generation, final Camera callbackCamera) {
        final boolean[] current = {false};
        try {
            halOwnershipGate.ownerCall(generation, new Runnable() {
                @Override public void run() {
                    stateLock.lock();
                    try {
                        current[0] = activeCamera == callbackCamera
                                && activeCameraGeneration == generation
                                && activeAttempt != null
                                && activeAttempt.generation == generation
                                && !emergencyClaimed;
                    } finally {
                        stateLock.unlock();
                    }
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            current[0] = true;
        }
        if (current[0]) failAttempt(generation, CAMERA_PREVIEW_FAILED);
    }

    private void onPreviewBuffer(byte[] buffer, Camera callbackCamera, long generation) {
        PreviewDispatch dispatch = claimPreviewBuffer(buffer, callbackCamera, generation);
        if (dispatch == null) return;
        if (dispatch.diagnosticCode != null) {
            failAttempt(generation, dispatch.diagnosticCode);
        } else if (dispatch.first) {
            deliverFirstFrame(generation, dispatch.listener, dispatch.descriptor, dispatch.frame);
        } else {
            deliverNextFrame(generation, dispatch.listener, dispatch.frame);
        }
    }

    private PreviewDispatch claimPreviewBuffer(final byte[] buffer,
            final Camera callbackCamera, final long generation) {
        final PreviewDispatch[] dispatch = new PreviewDispatch[1];
        try {
            halOwnershipGate.ownerCall(generation, new Runnable() {
                @Override public void run() {
                    if (!ownerAttemptCurrent(generation)) return;
                    if (activeCamera != callbackCamera
                            || activeCameraGeneration != generation
                            || buffer == null || buffer.length != BUFFER_BYTES) {
                        dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                        return;
                    }
                    IdentityHashMap<byte[], BufferLease> registry = bufferRegistry;
                    BufferLease lease = registry == null ? null : registry.get(buffer);
                    if (lease == null || lease.leased) {
                        dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                        return;
                    }

                    Listener listener;
                    CameraDescriptor descriptor;
                    CameraAttemptStateMachine.FrameClaim frameClaim;
                    long nextFrameId;
                    stateLock.lock();
                    try {
                        AttemptRecord attempt = activeAttempt;
                        if (attempt == null || attempt.generation != generation
                                || emergencyClaimed || activeListener == null) return;
                        frameClaim = machine.claimFrame(generation);
                        if (frameClaim == CameraAttemptStateMachine.FrameClaim.STALE) return;
                        nextFrameId = nextPositiveFrameId();
                        if (nextFrameId == 0L) {
                            closeRequested = true;
                            dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                            return;
                        }
                        listener = activeListener;
                        descriptor = attempt.descriptor;
                    } finally {
                        stateLock.unlock();
                    }
                    if (descriptor == null) {
                        dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                        return;
                    }

                    CameraOrientationPolicy.Transform transform = descriptor.transform();
                    FaceFrame frame = null;
                    try {
                        frame = new FaceFrame(nextFrameId, buffer,
                                PREVIEW_WIDTH, PREVIEW_HEIGHT,
                                transform.sdkRotationDegrees(), transform.sdkMirror(),
                                transform.jpegRotationDegrees(), transform.jpegMirror(),
                                new FaceFrame.Releaser() {
                                    @Override public void release(byte[] zeroedNv21) {
                                        returnZeroedBuffer(generation, zeroedNv21);
                                    }
                                });
                        lease.leased = true;
                        dispatch[0] = PreviewDispatch.frame(frame, listener, descriptor,
                                frameClaim == CameraAttemptStateMachine.FrameClaim.FIRST);
                    } catch (RuntimeException | LinkageError failed) {
                        if (frame != null) frame.close();
                        else Arrays.fill(buffer, (byte) 0);
                        dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                    } catch (OutOfMemoryError failed) {
                        if (frame != null) frame.close();
                        else Arrays.fill(buffer, (byte) 0);
                        dispatch[0] = PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
                    }
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            return PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
        } catch (OutOfMemoryError ignored) {
            return PreviewDispatch.failure(CAMERA_PREVIEW_FAILED);
        }
        return dispatch[0];
    }

    private long nextPositiveFrameId() {
        if (frameId == Long.MAX_VALUE) return 0L;
        frameId++;
        return frameId > 0L ? frameId : 0L;
    }

    private void returnZeroedBuffer(final long generation, final byte[] buffer) {
        if (buffer == null || buffer.length != BUFFER_BYTES) return;
        try {
            boolean posted = postOwner(new Runnable() {
                @Override public void run() { recycleOnOwner(generation, buffer); }
            });
            if (!posted && bufferReturnStillCurrent(generation)) {
                failAttempt(generation, CAMERA_PREVIEW_FAILED);
            }
        } catch (RuntimeException | LinkageError ignored) {
            forceClosedCleanupNoThrow(generation);
        }
    }

    private void recycleOnOwner(final long generation, final byte[] buffer) {
        final boolean[] invalid = {false};
        try {
            halOwnershipGate.ownerCall(generation, new Runnable() {
                @Override public void run() {
                    Camera camera;
                    BufferLease lease;
                    stateLock.lock();
                    try {
                        if (!machine.mayReturnBuffer(generation)
                                || activeAttempt == null
                                || activeAttempt.generation != generation
                                || emergencyClaimed) return;
                        camera = activeCamera;
                        IdentityHashMap<byte[], BufferLease> registry = bufferRegistry;
                        lease = registry == null ? null : registry.get(buffer);
                        if (activeCameraGeneration != generation || camera == null
                                || buffer.length != BUFFER_BYTES
                                || lease == null || !lease.leased) {
                            invalid[0] = true;
                            return;
                        }
                    } finally {
                        stateLock.unlock();
                    }
                    try {
                        requireOwnerCurrent(generation);
                        camera.addCallbackBuffer(buffer);
                        lease.leased = false;
                    } catch (RuntimeException | LinkageError ignored) {
                        invalid[0] = true;
                    }
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            invalid[0] = true;
        }
        if (invalid[0]) failAttempt(generation, CAMERA_PREVIEW_FAILED);
    }

    private void cleanupAttempt(final AttemptRecord attempt) {
        if (!claimCleanupExecution(attempt)) return;
        removeSurfaceCallbackOnce(attempt);
        final boolean[] releaseResult = {false};
        boolean cleanupRan = false;
        try {
            cleanupRan = halOwnershipGate.cleanupOnce(attempt.generation, new Runnable() {
                @Override public void run() {
                    Camera camera = null;
                    IdentityHashMap<byte[], BufferLease> registry = null;
                    if (activeCameraGeneration == attempt.generation) {
                        camera = activeCamera;
                        registry = bufferRegistry;
                        activeCamera = null;
                        activeCameraGeneration = 0L;
                        bufferRegistry = null;
                    }
                    boolean released = camera == null || releaseCamera(camera);
                    if (uncertainCameraGeneration == attempt.generation) released = false;
                    if (released) wipeNonLeasedBuffers(registry);
                    releaseResult[0] = released;
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            releaseResult[0] = false;
        }
        boolean released = cleanupRan && releaseResult[0];
        stateLock.lock();
        try {
            if (!released) closeRequested = true;
            if (activeAttempt == attempt) {
                machine.finishStop(released);
                if (closeRequested) machine.close();
                activeAttempt = null;
                activeListener = null;
                emergencyClaimed = !released;
            }
        } finally {
            stateLock.unlock();
            attempt.cleanupComplete.countDown();
        }
    }

    private void cleanupAfterPostRejected(final AttemptRecord attempt) {
        stateLock.lock();
        try {
            closeRequested = true;
            if (activeAttempt == attempt) {
                emergencyClaimed = true;
                activeListener = null;
                if (machine.state() != CameraAttemptStateMachine.State.STOPPING
                        && machine.state() != CameraAttemptStateMachine.State.CLOSED) {
                    machine.beginStop();
                }
            }
        } finally {
            stateLock.unlock();
        }
        removeSurfaceCallbackOnce(attempt);
        if (!claimCleanupExecution(attempt)) {
            awaitUninterruptibly(attempt.cleanupComplete);
            return;
        }

        final boolean[] releaseResult = {false};
        boolean cleanupRan = false;
        try {
            cleanupRan = halOwnershipGate.cleanupOnce(attempt.generation, new Runnable() {
                @Override public void run() {
                    Camera camera = null;
                    IdentityHashMap<byte[], BufferLease> registry = null;
                    if (activeCameraGeneration == attempt.generation) {
                        camera = activeCamera;
                        registry = bufferRegistry;
                        activeCamera = null;
                        activeCameraGeneration = 0L;
                        bufferRegistry = null;
                    }
                    boolean released = camera == null || releaseCamera(camera);
                    if (uncertainCameraGeneration == attempt.generation) released = false;
                    if (released) wipeNonLeasedBuffers(registry);
                    releaseResult[0] = released;
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            releaseResult[0] = false;
        }
        boolean released = cleanupRan && releaseResult[0];
        stateLock.lock();
        try {
            if (!released) closeRequested = true;
            if (activeAttempt == attempt) {
                machine.finishStop(released);
                if (closeRequested) machine.close();
                activeAttempt = null;
                activeListener = null;
                emergencyClaimed = true;
            }
        } finally {
            stateLock.unlock();
            attempt.cleanupComplete.countDown();
        }
    }

    private boolean releaseCamera(Camera camera) {
        try {
            camera.setPreviewCallbackWithBuffer(null);
        } catch (RuntimeException | LinkageError ignored) { }
        try {
            camera.setErrorCallback(null);
        } catch (RuntimeException | LinkageError ignored) { }
        try {
            camera.stopPreview();
        } catch (RuntimeException | LinkageError ignored) { }

        boolean firstReleaseFailed = false;
        try {
            camera.release();
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            firstReleaseFailed = true;
        }
        if (firstReleaseFailed) {
            try {
                camera.release();
                return true;
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private void wipeNonLeasedBuffers(IdentityHashMap<byte[], BufferLease> registry) {
        if (registry == null) return;
        for (Map.Entry<byte[], BufferLease> entry : registry.entrySet()) {
            byte[] buffer = entry.getKey();
            BufferLease lease = entry.getValue();
            if (lease.leased) continue;
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void deliverFirstFrame(long generation, Listener listener,
            CameraDescriptor descriptor, FaceFrame ownedFrame) {
        stateLock.lock();
        try {
            if (activeAttempt == null || activeAttempt.generation != generation
                    || activeListener != listener || emergencyClaimed
                    || machine.claimFrame(generation)
                    == CameraAttemptStateMachine.FrameClaim.STALE) {
                ownedFrame.close();
                return;
            }
        } finally {
            stateLock.unlock();
        }

        boolean readyFailed = false;
        try {
            listener.onCameraReady(descriptor);
        } catch (RuntimeException | LinkageError ignored) {
            readyFailed = true;
        }
        if (readyFailed) ownedFrame.close();
        if (readyFailed) {
            handleReadyCallbackFailure(generation, listener);
            return;
        }
        if (!canDeliverFrame(generation, listener)) {
            ownedFrame.close();
            return;
        }
        FaceFrame transfer = ownedFrame;
        ownedFrame = null;
        try {
            listener.onPreviewFrame(transfer);
        } catch (RuntimeException | LinkageError ignored) {
            handleFrameCallbackFailure(generation, listener);
        }
    }

    private void deliverNextFrame(long generation, Listener listener, FaceFrame ownedFrame) {
        if (!canDeliverFrame(generation, listener)) {
            ownedFrame.close();
            return;
        }
        FaceFrame transfer = ownedFrame;
        ownedFrame = null;
        try {
            listener.onPreviewFrame(transfer);
        } catch (RuntimeException | LinkageError ignored) {
            handleFrameCallbackFailure(generation, listener);
        }
    }

    private boolean canDeliverFrame(long generation, Listener listener) {
        stateLock.lock();
        try {
            return activeAttempt != null && activeAttempt.generation == generation
                    && activeListener == listener && !emergencyClaimed
                    && machine.state() == CameraAttemptStateMachine.State.RUNNING;
        } finally {
            stateLock.unlock();
        }
    }

    private void handleReadyCallbackFailure(long generation, Listener listener) {
        terminateBrokenListener(generation, listener);
    }

    private void handleFrameCallbackFailure(long generation, Listener listener) {
        terminateBrokenListener(generation, listener);
    }

    private void notifyErrorOnce(Listener listener, String diagnosticCode) {
        if (listener == null) return;
        try {
            listener.onCameraError(SAFE_CAMERA_ERROR, diagnosticCode);
        } catch (RuntimeException | LinkageError ignored) {
            // A failed customer callback cannot retain Camera ownership.
        }
    }

    private void terminateBrokenListener(long generation, Listener listener) {
        AttemptRecord attempt = null;
        stateLock.lock();
        try {
            if (activeAttempt != null && activeAttempt.generation == generation
                    && activeListener == listener
                    && machine.beginStop()) {
                activeAttempt.failureClaimed = true;
                activeListener = null;
                attempt = activeAttempt;
            }
        } finally {
            stateLock.unlock();
        }
        if (attempt != null) executeCleanupAndWait(attempt);
    }

    private void failAttempt(long generation, String diagnosticCode) {
        AttemptRecord attempt = null;
        Listener listener = null;
        stateLock.lock();
        try {
            if (activeAttempt != null && activeAttempt.generation == generation
                    && !activeAttempt.failureClaimed
                    && machine.state() != CameraAttemptStateMachine.State.STOPPING
                    && machine.state() != CameraAttemptStateMachine.State.CLOSED) {
                activeAttempt.failureClaimed = true;
                listener = activeListener;
                activeListener = null;
                if (machine.beginStop()) attempt = activeAttempt;
            }
        } finally {
            stateLock.unlock();
        }
        if (attempt == null) return;
        executeCleanupAndWait(attempt);
        notifyErrorOnce(listener, diagnosticCode);
    }

    private void executeCleanupAndWait(AttemptRecord attempt) {
        removeSurfaceCallbackOnce(attempt);
        if (isOwnerThread()) {
            cleanupAttempt(attempt);
        } else if (!postOwner(new Runnable() {
            @Override public void run() { cleanupAttempt(attempt); }
        })) {
            cleanupAfterPostRejected(attempt);
        }
        awaitUninterruptibly(attempt.cleanupComplete);
    }

    private boolean claimCleanupExecution(AttemptRecord attempt) {
        stateLock.lock();
        try {
            if (attempt.cleanupExecutionClaimed) return false;
            attempt.cleanupExecutionClaimed = true;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private void removeSurfaceCallbackOnce(AttemptRecord attempt) {
        boolean remove = false;
        boolean waitForRegistration = false;
        long generation = attempt.generation;
        stateLock.lock();
        try {
            if (!machine.claimSurfaceRemoval(generation)) return;
            if (attempt.surfaceRegistrationComplete.getCount() == 0L) {
                remove = true;
            } else if (attempt.registrationThread == Thread.currentThread()) {
                attempt.removalPending = true;
            } else {
                waitForRegistration = true;
            }
        } finally {
            stateLock.unlock();
        }
        if (waitForRegistration) {
            awaitUninterruptibly(attempt.surfaceRegistrationComplete);
            remove = true;
        }
        if (remove) removeSurfaceCallbackDirect(attempt);
    }

    private void removeSurfaceCallbackDirect(AttemptRecord attempt) {
        try {
            SurfaceHolder holder = attempt.holder;
            SurfaceHolder.Callback surfaceCallback = attempt.surfaceCallback;
            holder.removeCallback(surfaceCallback);
        } catch (RuntimeException | LinkageError ignored) {
            // Registration ownership is terminal even if a vendor holder rejects removal.
        }
    }

    private boolean ownerAttemptCurrent(long generation) {
        stateLock.lock();
        try {
            if (emergencyClaimed || activeAttempt == null
                    || activeAttempt.generation != generation) return false;
            CameraAttemptStateMachine.State state = machine.state();
            return state == CameraAttemptStateMachine.State.STARTING
                    || state == CameraAttemptStateMachine.State.AWAITING_FIRST_FRAME
                    || state == CameraAttemptStateMachine.State.RUNNING;
        } finally {
            stateLock.unlock();
        }
    }

    private boolean bufferReturnStillCurrent(long generation) {
        stateLock.lock();
        try {
            return activeAttempt != null && activeAttempt.generation == generation
                    && machine.mayReturnBuffer(generation) && !emergencyClaimed;
        } finally {
            stateLock.unlock();
        }
    }

    private void requireOwnerCurrent(long generation) {
        if (!ownerAttemptCurrent(generation)) throw new AttemptEndedException();
    }

    private int displayRotationFor(long generation) {
        stateLock.lock();
        try {
            if (activeAttempt == null || activeAttempt.generation != generation) {
                throw new AttemptEndedException();
            }
            return ((GenerationSurfaceCallback) activeAttempt.surfaceCallback)
                    .displayRotationDegrees;
        } finally {
            stateLock.unlock();
        }
    }

    private boolean postOwner(Runnable operation) {
        try {
            return operation != null && ownerHandler.post(operation);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean isOwnerThread() {
        return Looper.myLooper() == ownerThread.getLooper();
    }

    private void joinOwnerUninterruptibly() {
        boolean interrupted = false;
        for (;;) {
            try {
                ownerThread.join();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static FaceCaptureSession.Cancellable completedHandle() {
        return COMPLETED_HANDLE;
    }
}
