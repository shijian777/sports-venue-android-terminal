package com.codex.lockertest.face;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AndroidFaceJpegEncoder implements FaceJpegEncoder {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int NV21_BYTES = 460800;
    private static final int JPEG_QUALITY = 85;
    private static final int MAX_BYTES = 1048576;
    private static final String SAFE_MESSAGE = "人脸照片处理失败，请重新尝试";
    private static final String BUSY_CODE = "ENCODER_BUSY";
    private static final String CLOSED_CODE = "ENCODER_CLOSED";
    private static final String SCHEDULING_CODE = "ENCODER_SCHEDULING_FAILED";
    private static final String FAILURE_CODE = "JPEG_ENCODING_FAILED";
    private static final FaceCaptureSession.Cancellable COMPLETED_HANDLE = () -> { };

    private final FaceJpegEncoderOwnerGate ownerGate = new FaceJpegEncoderOwnerGate();
    private final ExecutorService executor;
    private volatile Thread workerThread;

    public AndroidFaceJpegEncoder() {
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread result = new Thread(runnable, "face-jpeg-encoder");
                workerThread = result;
                return result;
            }
        });
    }

    @Override
    public FaceCaptureSession.Cancellable encode(
            long frameId, FaceFrame.Borrow ownedFrame, Callback callback) {
        FaceJpegEncoderAdmission.validate(frameId, ownedFrame, callback);
        Job job;
        try {
            job = new Job(frameId, ownedFrame, callback);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
            try {
                FaceJpegEncoderRejection.reject(
                        frameId, ownedFrame, callback, FAILURE_CODE);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ignoredAgain) { }
            return COMPLETED_HANDLE;
        }

        FaceJpegEncoderOwnerGate.Admission admission = ownerGate.tryAccept(job);
        if (admission == FaceJpegEncoderOwnerGate.Admission.BUSY) {
            try {
                FaceJpegEncoderRejection.reject(frameId, ownedFrame, callback, BUSY_CODE);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
            return COMPLETED_HANDLE;
        }
        if (admission == FaceJpegEncoderOwnerGate.Admission.CLOSED) {
            try {
                FaceJpegEncoderRejection.reject(frameId, ownedFrame, callback, CLOSED_CODE);
            } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
            return COMPLETED_HANDLE;
        }

        try {
            executor.execute(job);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
            if (job.prepareSchedulingFailure()) {
                try {
                    FaceJpegEncoderRejection.reject(
                            frameId, ownedFrame, callback, SCHEDULING_CODE);
                } catch (RuntimeException | LinkageError | OutOfMemoryError ignoredAgain) { }
                finally {
                    job.completeSchedulingFailureDelivery();
                }
            } else {
                job.completeSchedulingFailureWithoutDelivery();
            }
            return COMPLETED_HANDLE;
        }
        return job;
    }

    @Override
    public void close() {
        Job claimed = null;
        try {
            Object active = ownerGate.closeAndTakeActive();
            if (active instanceof Job) claimed = (Job) active;
        } catch (RuntimeException | LinkageError ignored) { }
        if (claimed != null) claimed.cancel();

        boolean shutdownRequested = false;
        if (ownerGate.isClosed()) {
            try {
                executor.shutdown();
                shutdownRequested = true;
            } catch (RuntimeException | LinkageError ignored) { }
        }
        if (shutdownRequested) {
            if (Thread.currentThread() != workerThread) {
                try { awaitTerminationUninterruptibly(); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    private void awaitTerminationUninterruptibly() {
        boolean interrupted = false;
        for (;;) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private final class Job implements Runnable, FaceCaptureSession.Cancellable {
        private final long frameId;
        private final FaceFrame.Borrow ownedFrame;
        private final Callback callback;
        private final Callback completionCallback;
        private final FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();

        Job(long frameId, FaceFrame.Borrow ownedFrame, Callback callback) {
            this.frameId = frameId;
            this.ownedFrame = ownedFrame;
            this.callback = callback;
            this.completionCallback = new CompletionCallback(callback);
        }

        @Override
        public void run() {
            if (gate.tryStart() == FaceJpegEncoderJobGate.Start.CANCELLED) {
                try { FaceJpegEncoderAdmission.closeBorrowQuietly(ownedFrame); }
                finally {
                    gate.completeCleanup();
                    try { clearOwner(); }
                    finally { gate.completeTerminal(); }
                }
                return;
            }

            byte[] encoded = null;
            FaceJpegEncoder.OwnedJpeg wrapper = null;
            try {
                encoded = encodeFullFrame();
            } catch (EncodingCancelledException ignored) {
                // Cancellation is a silent terminal path.
            } catch (IOException ignored) {
                // A bounded stream failure becomes one fixed diagnostic below.
            } catch (RuntimeException | LinkageError ignored) {
                // Android/native failures are collapsed to a fixed diagnostic.
            } catch (OutOfMemoryError ignored) {
                // Cleanup has already run; best-effort fixed failure follows.
            }

            if (encoded != null) {
                try {
                    wrapper = new FaceJpegEncoder.OwnedJpeg(encoded);
                    encoded = null;
                } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
            }
            if (wrapper == null) wipe(encoded);

            if (!gate.tryClaimDelivery()) {
                try {
                    if (wrapper != null) {
                        try { wrapper.close(); }
                        catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
                    }
                    wipe(encoded);
                } finally {
                    try { clearOwner(); }
                    finally { gate.completeTerminal(); }
                }
                return;
            }

            if (wrapper != null) {
                try {
                    FaceJpegEncoderCompletion.deliverAndClear(
                            ownerGate, this, gate, frameId, wrapper, completionCallback);
                } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
                    try {
                        try { wrapper.close(); }
                        catch (RuntimeException | LinkageError | OutOfMemoryError ignoredAgain) { }
                        gate.completeDelivery();
                    } finally {
                        try { clearOwner(); }
                        finally { gate.completeTerminal(); }
                    }
                }
            } else {
                try {
                    FaceJpegEncoderCompletion.failAndClear(
                            ownerGate, this, gate, frameId,
                            callback, SAFE_MESSAGE, FAILURE_CODE);
                } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
                    try { gate.completeDelivery(); }
                    finally {
                        try { clearOwner(); }
                        finally { gate.completeTerminal(); }
                    }
                }
            }
        }

        private byte[] encodeFullFrame() throws IOException {
            WipingBoundedOutputStream intermediateOutput = null;
            WipingBoundedOutputStream finalOutput = null;
            byte[] intermediateBytes = null;
            byte[] finalBytes = null;
            byte[] successBytes = null;
            Bitmap intermediate = null;
            Bitmap rotated = null;
            Bitmap validation = null;
            try {
                requireNotCancelled();
                if (ownedFrame.width() != WIDTH
                        || ownedFrame.height() != HEIGHT
                        || ownedFrame.nv21().length != NV21_BYTES
                        || ownedFrame.jpegMirror()) {
                    throw new IllegalArgumentException("invalid full-frame NV21 input");
                }
                int rotation = ownedFrame.jpegRotationDegrees();
                JpegValidationPolicy.expectedWidth(rotation);
                JpegValidationPolicy.expectedHeight(rotation);
                byte[] nv21 = ownedFrame.nv21();
                requireNotCancelled();

                YuvImage yuvImage = new YuvImage(
                        nv21, ImageFormat.NV21, WIDTH, HEIGHT, null);
                intermediateOutput = new WipingBoundedOutputStream(MAX_BYTES);
                requireNotCancelled();
                if (!yuvImage.compressToJpeg(
                        new Rect(0, 0, WIDTH, HEIGHT), JPEG_QUALITY, intermediateOutput)) {
                    throw new IllegalStateException("NV21 compression failed");
                }
                requireNotCancelled();
                intermediateBytes = intermediateOutput.toByteArray();
                requireNotCancelled();
                intermediate = BitmapFactory.decodeByteArray(
                        intermediateBytes, 0, intermediateBytes.length);
                if (intermediate == null
                        || intermediate.getWidth() != WIDTH
                        || intermediate.getHeight() != HEIGHT) {
                    throw new IllegalStateException("intermediate JPEG is invalid");
                }
                requireNotCancelled();

                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                rotated = Bitmap.createBitmap(intermediate, 0, 0, WIDTH, HEIGHT,
                        matrix, true);
                if (rotated == null) throw new IllegalStateException("rotation failed");
                requireNotCancelled();

                finalOutput = new WipingBoundedOutputStream(MAX_BYTES);
                if (!rotated.compress(
                        Bitmap.CompressFormat.JPEG, JPEG_QUALITY, finalOutput)) {
                    throw new IllegalStateException("final JPEG compression failed");
                }
                requireNotCancelled();
                finalBytes = finalOutput.toByteArray();
                requireNotCancelled();
                if (!JpegValidationPolicy.hasValidEnvelope(finalBytes)) {
                    throw new IllegalStateException("final JPEG envelope is invalid");
                }
                validation = BitmapFactory.decodeByteArray(
                        finalBytes, 0, finalBytes.length);
                if (validation == null
                        || validation.getWidth() != JpegValidationPolicy.expectedWidth(rotation)
                        || validation.getHeight() != JpegValidationPolicy.expectedHeight(rotation)) {
                    throw new IllegalStateException("final JPEG dimensions are invalid");
                }
                requireNotCancelled();
                successBytes = finalBytes;
                finalBytes = null;
                return successBytes;
            } finally {
                try {
                    try { ownedFrame.close(); }
                    catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
                    recycleDistinct(intermediate, rotated, validation);
                    wipe(intermediateBytes);
                    wipe(finalBytes);
                    closeStream(intermediateOutput);
                    closeStream(finalOutput);
                } finally {
                    gate.completeCleanup();
                }
            }
        }

        private void requireNotCancelled() {
            if (gate.isCancellationRequested()) throw new EncodingCancelledException();
        }

        private boolean prepareSchedulingFailure() {
            FaceJpegEncoderJobGate.Start start = gate.tryStart();
            FaceJpegEncoderAdmission.closeBorrowQuietly(ownedFrame);
            gate.completeCleanup();
            return start == FaceJpegEncoderJobGate.Start.RUN && gate.tryClaimDelivery();
        }

        private void completeSchedulingFailureDelivery() {
            try { gate.completeDelivery(); }
            finally {
                try { clearOwner(); }
                finally { gate.completeTerminal(); }
            }
        }

        private void completeSchedulingFailureWithoutDelivery() {
            try { clearOwner(); }
            finally { gate.completeTerminal(); }
        }

        private void clearOwner() {
            ownerGate.clear(this);
        }

        @Override public void cancel() {
            try { gate.cancel(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private static final class CompletionCallback implements Callback {
        private final Callback target;

        CompletionCallback(Callback target) {
            this.target = target;
        }

        @Override public void onEncoded(long frameId, OwnedJpeg ownedJpeg) {
            FaceJpegEncoderDelivery.deliver(frameId, ownedJpeg, target);
        }

        @Override public void onFailure(
                long frameId, String safeMessage, String diagnosticCode) {
            try { target.onFailure(frameId, safeMessage, diagnosticCode); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private static final class EncodingCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void recycleDistinct(Bitmap first, Bitmap second, Bitmap third) {
        recycleOne(first);
        if (second != first) recycleOne(second);
        if (third != first && third != second) recycleOne(third);
    }

    private static void recycleOne(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            if (!bitmap.isRecycled()) bitmap.recycle();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
    }

    private static void closeStream(WipingBoundedOutputStream stream) {
        if (stream == null) return;
        try { stream.close(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
    }

    private static void wipe(byte[] bytes) {
        if (bytes == null) return;
        try { Arrays.fill(bytes, (byte) 0); }
        catch (RuntimeException | LinkageError | OutOfMemoryError ignored) { }
    }
}
