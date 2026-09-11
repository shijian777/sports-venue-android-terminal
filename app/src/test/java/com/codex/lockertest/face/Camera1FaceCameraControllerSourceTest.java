package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

public final class Camera1FaceCameraControllerSourceTest {
    @Test
    public void publicSurfaceUsesOwnedFaceFrameAndStartupOnlyHandle() throws Exception {
        String source = controller();
        assertContains(source, "public final class Camera1FaceCameraController implements AutoCloseable");
        assertContains(source, "void onCameraReady(CameraDescriptor descriptor)");
        assertContains(source, "void onPreviewFrame(FaceFrame ownedFrame)");
        assertContains(source, "void onCameraError(String safeMessage, String diagnosticCode)");
        assertContains(source, "public FaceCaptureSession.Cancellable start(");
        assertTrue(Pattern.compile(
                "public\\s+FaceCaptureSession\\.Cancellable\\s+start\\s*\\(\\s*"
                        + "SurfaceHolder\\s+holder\\s*,\\s*"
                        + "int\\s+displayRotationDegrees\\s*,\\s*"
                        + "Listener\\s+listener\\s*\\)", Pattern.DOTALL)
                .matcher(source).find());
        String descriptor = methodSlice(source,
                "public static final class CameraDescriptor {",
                "public interface Listener {");
        assertContains(descriptor, "public int cameraId()");
        assertContains(descriptor, "public int sensorOrientation()");
        assertContains(descriptor, "public int width()");
        assertContains(descriptor, "public int height()");
        assertContains(descriptor, "public int fpsMin()");
        assertContains(descriptor, "public int fpsMax()");
        assertContains(descriptor,
                "public CameraOrientationPolicy.Transform transform()");
        assertContains(source, "public void stop()");
        assertContains(source, "public boolean isRunning()");
        assertContains(source, "public void close()");
        assertFalse(source.contains("onPreviewFrame(byte[]"));
        assertFalse(source.contains("Context"));
        assertFalse(source.contains("Activity"));
        assertFalse(source.contains("android.view.View"));
    }

    @Test
    public void frontCameraExactFormatSizeAndFpsAreVerifiedAfterSet() throws Exception {
        String source = controller();
        assertContains(source, "Camera.getNumberOfCameras()");
        assertContains(source, "Camera.getCameraInfo(");
        assertContains(source, "Camera.CameraInfo.CAMERA_FACING_FRONT");
        assertContains(source, "Camera.open(cameraId)");
        assertFalse(Pattern.compile("Camera\\.open\\s*\\(\\s*\\)").matcher(source).find());
        assertFalse(source.toLowerCase().contains("back camera"));
        assertFalse(source.contains("CAMERA_FACING_BACK"));
        assertContains(source, "PREVIEW_WIDTH = 640");
        assertContains(source, "PREVIEW_HEIGHT = 480");
        assertContains(source, "ImageFormat.NV21");
        assertContains(source, "getSupportedPreviewSizes()");
        assertContains(source, "getSupportedPreviewFormats()");
        assertContains(source, "getSupportedPreviewFpsRange()");
        assertContains(source, "CameraOrientationPolicy.selectFpsRange");
        assertContains(source, "parameters.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)");
        assertContains(source, "parameters.setPreviewFormat(ImageFormat.NV21)");
        assertContains(source, "parameters.setPreviewFpsRange(selectedFps[0], selectedFps[1])");
        assertContains(source, "camera.getParameters()");
        assertContains(source, "verifyReadback");
        String readback = methodSlice(source, "private void verifyReadback(",
                "private void allocateCallbackBuffers(");
        assertContains(readback, "readback.getPreviewSize()");
        assertContains(readback, "size.width != PREVIEW_WIDTH");
        assertContains(readback, "size.height != PREVIEW_HEIGHT");
        assertContains(readback, "readback.getPreviewFormat() != ImageFormat.NV21");
        assertContains(readback, "readback.getPreviewFpsRange(actualFps)");
        assertContains(readback, "actualFps[0] != selectedFps[0]");
        assertContains(readback, "actualFps[1] != selectedFps[1]");
    }

    @Test
    public void previewUsesDisplayTransformSurfaceErrorCallbackAndThreeIdentityBuffers()
            throws Exception {
        String source = controller();
        assertContains(source, "setDisplayOrientation(transform.previewDisplayOrientation())");
        assertContains(source, "setPreviewDisplay(holder)");
        assertContains(source, "setErrorCallback(");
        assertContains(source, "setPreviewCallbackWithBuffer(");
        assertFalse(source.contains("setPreviewCallback("));
        assertContains(source, "CALLBACK_BUFFER_COUNT = 3");
        assertContains(source, "BUFFER_BYTES = 460800");
        assertContains(source, "new byte[BUFFER_BYTES]");
        assertContains(source, "(long) PREVIEW_WIDTH * (long) PREVIEW_HEIGHT");
        assertContains(source, "IdentityHashMap<byte[], BufferLease>");
        assertContains(source, "buffer.length != BUFFER_BYTES");
        assertContains(source, "camera.addCallbackBuffer(buffer)");
        assertContains(source, "Arrays.fill(buffer, (byte) 0)");
        assertContains(source, "if (lease == null || lease.leased)");
        assertContains(source, "failAttempt(generation");
        assertContains(source, "nextPositiveFrameId");
        assertContains(source, "frameId == Long.MAX_VALUE");
    }

    @Test
    public void faceFrameReleaserCapturesGenerationNotCameraAndReturnRevalidatesIdentity()
            throws Exception {
        String source = controller();
        String preview = methodSlice(source, "private void onPreviewBuffer(",
                "private void returnZeroedBuffer(");
        String recycle = methodSlice(source, "private void returnZeroedBuffer(",
                "private void recycleOnOwner(");
        assertContains(preview, "new FaceFrame(");
        assertContains(preview, "generation");
        assertContains(preview, "returnZeroedBuffer");
        assertFalse(Pattern.compile("Releaser[\\s\\S]{0,300}Camera\\s+")
                .matcher(preview).find());
        assertContains(recycle, "generation");
        assertContains(recycle, "BUFFER_BYTES");
        assertContains(source, "lease.leased");
        assertContains(source, "machine.mayReturnBuffer(generation)");
        assertContains(source, "postOwner");
        assertContains(source, "recycleOnOwner");
    }

    @Test
    public void firstFrameCompletesStartupBeforeReadyAndRechecksAfterReentrantReady()
            throws Exception {
        String source = controller();
        String first = methodSlice(source, "private void deliverFirstFrame(",
                "private void deliverNextFrame(");
        assertContains(first, "machine.claimFrame(generation)");
        assertContains(first, "listener.onCameraReady(descriptor)");
        assertContains(first, "canDeliverFrame(generation, listener)");
        assertContains(first, "ownedFrame.close()");
        assertTrue(first.indexOf("listener.onCameraReady(descriptor)")
                < first.indexOf("canDeliverFrame(generation, listener)"));
        assertTrue(first.indexOf("canDeliverFrame(generation, listener)")
                < first.indexOf("listener.onPreviewFrame(transfer)"));
        assertContains(first, "ownedFrame = null");
        String next = methodSlice(source, "private void deliverNextFrame(",
                "private boolean canDeliverFrame(");
        assertContains(next, "ownedFrame = null");
        assertContains(next, "listener.onPreviewFrame(transfer)");
    }

    @Test
    public void ownerThreadHalGateAndPostRejectedEmergencyHaveNoTimeoutEscape()
            throws Exception {
        String source = controller();
        assertContains(source, "new HandlerThread(");
        assertContains(source,
                "private final CameraHalGate halOwnershipGate = new CameraHalGate()");
        assertContains(source, "halOwnershipGate.ownerCall(");
        assertContains(source, "halOwnershipGate.cleanupOnce(");
        assertContains(source,
                "private final CameraAttemptStateMachine machine = new CameraAttemptStateMachine()");
        assertContains(source, "machine.beginStart()");
        assertContains(source, "machine.markPreviewActive(");
        assertContains(source, "machine.beginStop()");
        assertContains(source, "machine.finishStop(");
        assertContains(source, "emergencyClaimed");
        assertContains(source, "cleanupAfterPostRejected");
        assertContains(source, "awaitUninterruptibly");
        assertContains(source, "Thread.currentThread().interrupt()");
        assertContains(source, "Looper.myLooper() == ownerThread.getLooper()");
        assertFalse(source.contains("System.nanoTime"));
        assertFalse(source.contains("currentTimeMillis"));
        assertFalse(source.contains("TimeUnit.SECONDS"));
        assertFalse(Pattern.compile("\\.await\\s*\\([^)]*,[^)]*\\)")
                .matcher(source).find());
        assertFalse(Pattern.compile("\\.join\\s*\\([^)]*[0-9][^)]*\\)")
                .matcher(source).find());
    }

    @Test
    public void controllerActuallyWiresStateAndHalSeamsInEveryLifecycleMethod()
            throws Exception {
        String source = controller();
        String start = methodSlice(source,
                "public FaceCaptureSession.Cancellable start(", "public void stop()");
        assertContains(start, "machine.beginStart()");
        assertContains(start, "holder.addCallback(surfaceCallback)");

        String open = methodSlice(source, "private void openCameraOnOwner(",
                "private CameraConfiguration configureCamera(");
        assertContains(open, "halOwnershipGate.ownerCall(");
        assertContains(open, "Camera.open(cameraId)");
        assertContains(open, "machine.markPreviewActive(generation)");

        String configure = methodSlice(source, "private CameraConfiguration configureCamera(",
                "private void verifyReadback(");
        assertContains(configure, "CameraOrientationPolicy.selectFpsRange");
        assertContains(configure, "verifyReadback(");

        String preview = methodSlice(source, "private void onPreviewBuffer(",
                "private PreviewDispatch claimPreviewBuffer(");
        assertContains(preview, "claimPreviewBuffer(");
        assertFalse(preview.contains("halOwnershipGate"));
        assertFalse(preview.contains("synchronized"));
        assertTrue(preview.indexOf("claimPreviewBuffer(")
                < preview.indexOf("deliverFirstFrame("));

        String claim = methodSlice(source, "private PreviewDispatch claimPreviewBuffer(",
                "private void returnZeroedBuffer(");
        assertContains(claim, "halOwnershipGate.ownerCall(");
        assertContains(claim, "machine.claimFrame(generation)");

        String recycle = methodSlice(source, "private void recycleOnOwner(",
                "private void cleanupAttempt(");
        assertContains(recycle, "halOwnershipGate.ownerCall(");
        assertContains(recycle, "machine.mayReturnBuffer(generation)");

        String release = methodSlice(source, "private void cleanupAttempt(",
                "private void cleanupAfterPostRejected(");
        assertContains(release, "halOwnershipGate.cleanupOnce(");
        assertContains(release, "machine.finishStop(released)");

        String emergency = methodSlice(source, "private void cleanupAfterPostRejected(",
                "private boolean releaseCamera(");
        assertContains(emergency, "emergencyClaimed");
        assertContains(emergency, "halOwnershipGate.cleanupOnce(");
    }

    @Test
    public void surfaceRegistrationIsGenerationBoundRemovedOnceAndBusyIsNonMutating()
            throws Exception {
        String source = controller();
        assertContains(source, "implements SurfaceHolder.Callback");
        assertContains(source, "holder.addCallback(surfaceCallback)");
        assertContains(source, "removeSurfaceCallbackOnce");
        assertContains(source, "holder.removeCallback(surfaceCallback)");
        assertContains(source, "claimSurfaceRemoval(generation)");
        assertContains(source, "surface.isValid()");
        assertContains(source, "surfaceCreated");
        assertContains(source, "surfaceChanged");
        assertContains(source, "surfaceDestroyed");
        assertContains(source, "openRequested");
        assertContains(source, "CAMERA_BUSY");
        assertContains(source, "CAMERA_CONTROLLER_CLOSED");
        assertContains(source, "completedHandle()");
    }

    @Test
    public void cleanupAlwaysDetachesCallbacksRetriesReleaseAndProtectsUnknownArrays()
            throws Exception {
        String source = controller();
        String cleanup = methodSlice(source, "private boolean releaseCamera(",
                "private void wipeNonLeasedBuffers(");
        assertContains(cleanup, "setPreviewCallbackWithBuffer(null)");
        assertContains(cleanup, "setErrorCallback(null)");
        assertContains(cleanup, "stopPreview()");
        assertEquals(2, occurrences(cleanup, ".release()"));
        assertContains(source, "if (released) wipeNonLeasedBuffers");
        assertContains(source, "machine.finishStop(released)");
        assertContains(source, "removeSurfaceCallbackOnce");
        assertContains(source, "if (!released)");
        assertContains(source, "bufferRegistry = null");
        assertContains(cleanup, "boolean firstReleaseFailed");
        assertContains(cleanup, "if (firstReleaseFailed)");
        assertContains(source, "wipeNonLeasedBuffers");
        assertContains(source, "if (lease.leased) continue");
    }

    @Test
    public void fatalPathsUseFixedSafeDiagnosticsAndNeverLeakRawData() throws Exception {
        String source = controller();
        assertContains(source, "摄像头启动失败，请重新尝试");
        assertContains(source, "SURFACE_DESTROYED");
        assertContains(source, "CAMERA_OPEN_FAILED");
        assertContains(source, "CAMERA_CONFIGURATION_FAILED");
        assertContains(source, "CAMERA_PREVIEW_FAILED");
        assertContains(source, "RuntimeException | LinkageError");
        assertFalse(source.contains("getMessage()"));
        assertFalse(source.contains("printStackTrace"));
        assertFalse(source.contains("android.util.Log"));
        assertFalse(source.contains("System.out"));
        assertFalse(source.contains("System.err"));
        assertFalse(source.contains("Base64"));
        assertFalse(source.contains("FileOutputStream"));
        assertFalse(source.contains("java.io.File"));
        assertFalse(source.contains("FileWriter"));
        assertFalse(source.contains("RandomAccessFile"));
        assertFalse(source.contains("java.nio.file.Files"));
        assertFalse(source.contains("MediaStore"));
        assertFalse(source.contains("Environment"));
        assertFalse(source.contains("openFileOutput"));
        assertFalse(source.contains("ByteArrayOutputStream"));
        assertFalse(source.contains("getCacheDir"));
        assertFalse(source.contains("getExternal"));
    }

    @Test
    public void closeAndCancelAreNoThrowBoundariesAndOwnerNeverSelfJoins() throws Exception {
        String source = controller();
        assertContains(source, "private void cancelStartupNoThrow(");
        assertContains(source, "private void stopNoThrow(");
        assertContains(source, "catch (RuntimeException | LinkageError ignored)");
        assertContains(source, "quitSafely()");
        assertContains(source, "if (!isOwnerThread())");
        assertContains(source, "joinOwnerUninterruptibly");
    }

    @Test
    public void transferredFrameIsNeverTouchedAfterListenerEntry() throws Exception {
        String source = controller();
        String first = methodSlice(source, "private void deliverFirstFrame(",
                "private void deliverNextFrame(");
        assertOrdered(first, "machine.claimFrame(generation)",
                "listener.onCameraReady(descriptor)",
                "canDeliverFrame(generation, listener)",
                "ownedFrame = null", "listener.onPreviewFrame(transfer)");
        assertNoOwnedFrameUseAfterCallback(first);
        String next = methodSlice(source, "private void deliverNextFrame(",
                "private boolean canDeliverFrame(");
        assertOrdered(next, "canDeliverFrame(generation, listener)",
                "ownedFrame = null", "listener.onPreviewFrame(transfer)");
        assertNoOwnedFrameUseAfterCallback(next);
    }

    @Test
    public void callbacksAreOutsideOwnerAndHalLocksAndBrokenListenersTerminateOnce()
            throws Exception {
        String source = controller();
        assertContains(source, "handleReadyCallbackFailure");
        assertContains(source, "handleFrameCallbackFailure");
        assertContains(source, "notifyErrorOnce");
        assertContains(source, "if (readyFailed) ownedFrame.close()");
        String first = methodSlice(source, "private void deliverFirstFrame(",
                "private void deliverNextFrame(");
        assertFalse(first.contains("synchronized"));
        assertFalse(first.contains("halOwnershipGate.ownerCall"));
        String next = methodSlice(source, "private void deliverNextFrame(",
                "private boolean canDeliverFrame(");
        assertFalse(next.contains("synchronized"));
        assertFalse(next.contains("halOwnershipGate.ownerCall"));
        String readyFailure = methodSlice(source, "private void handleReadyCallbackFailure(",
                "private void handleFrameCallbackFailure(");
        assertFalse(readyFailure.contains("notifyErrorOnce"));
        String frameFailure = methodSlice(source, "private void handleFrameCallbackFailure(",
                "private void notifyErrorOnce(");
        assertFalse(frameFailure.contains("notifyErrorOnce"));
    }

    private static String controller() throws IOException {
        return read("app/src/main/java/com/codex/lockertest/face/Camera1FaceCameraController.java");
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int i = 0; i < 8 && candidate != null; i++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate project root");
    }

    private static String methodSlice(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue("missing " + startToken, start >= 0);
        assertTrue("missing " + endToken, end > start);
        return source.substring(start, end);
    }

    private static int occurrences(String source, String token) {
        int result = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            result++;
            from += token.length();
        }
        return result;
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing source token: " + token, source.contains(token));
    }

    private static void assertOrdered(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token, previous + 1);
            assertTrue("missing/out-of-order token: " + token, current > previous);
            previous = current;
        }
    }

    private static void assertNoOwnedFrameUseAfterCallback(String method) {
        int callback = method.indexOf("listener.onPreviewFrame(transfer)");
        assertTrue(callback >= 0);
        int statementEnd = method.indexOf(';', callback);
        String tail = method.substring(statementEnd + 1);
        assertFalse(tail.contains("ownedFrame"));
        assertFalse(tail.contains("transfer"));
    }
}
