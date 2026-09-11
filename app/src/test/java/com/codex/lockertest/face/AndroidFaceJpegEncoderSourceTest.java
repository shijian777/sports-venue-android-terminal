package com.codex.lockertest.face;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.Test;

public final class AndroidFaceJpegEncoderSourceTest {
    @Test
    public void publicEncoderSurfaceTransfersOwnedBorrowAndOwnedJpeg() throws Exception {
        String contract = faceContract();
        String encoder = encoder();
        assertContains(contract, "public interface FaceJpegEncoder extends AutoCloseable");
        assertContains(contract, "final class OwnedJpeg implements AutoCloseable");
        assertTrue(Pattern.compile("public\\s+(?:synchronized\\s+)?int\\s+size\\s*\\(\\s*\\)")
                .matcher(contract).find());
        assertTrue(Pattern.compile(
                "public\\s+(?:synchronized\\s+)?byte\\[\\]\\s+take\\s*\\(\\s*\\)")
                .matcher(contract).find());
        assertContains(contract, "void onEncoded(long frameId, OwnedJpeg ownedJpeg)");
        assertContains(contract, "void onFailure(long frameId, String safeMessage, String diagnosticCode)");
        assertContains(contract, "FaceCaptureSession.Cancellable encode(");
        assertTrue(Pattern.compile(
                "FaceCaptureSession\\.Cancellable\\s+encode\\s*\\(\\s*"
                        + "long\\s+frameId\\s*,\\s*"
                        + "FaceFrame\\.Borrow\\s+ownedFrame\\s*,\\s*"
                        + "Callback\\s+callback\\s*\\)", Pattern.DOTALL)
                .matcher(contract).find());
        assertContains(encoder, "public final class AndroidFaceJpegEncoder implements FaceJpegEncoder");
        assertContains(encoder, "newSingleThreadExecutor");
        assertContains(encoder,
                "private final FaceJpegEncoderOwnerGate ownerGate = new FaceJpegEncoderOwnerGate()");
        assertContains(encoder, "FaceJpegEncoderAdmission.validate(");
        assertContains(encoder, "ownerGate.tryAccept(");
        assertContains(encoder, "ownerGate.clear(");
        assertContains(encoder, "ownerGate.closeAndTakeActive()");
        assertContains(encoder, "FaceJpegEncoderRejection.reject(");
        assertContains(encoder, "new FaceJpegEncoderJobGate()");
        assertFalse(Pattern.compile(
                "FaceCaptureSession\\.Cancellable\\s+encode\\s*\\([^)]*byte\\s*\\[\\]")
                .matcher(contract).find());
        assertFalse(Pattern.compile("void\\s+onEncoded\\s*\\([^)]*byte\\s*\\[\\]")
                .matcher(contract).find());
    }

    @Test
    public void ownedJpegTakeAndCloseAreLinearizedInBothWinnerOrders() throws Exception {
        byte[] takeFirst = sampleJpeg(32);
        byte[] takeSnapshot = takeFirst.clone();
        FaceJpegEncoder.OwnedJpeg takenOwner = new FaceJpegEncoder.OwnedJpeg(takeFirst);
        assertEquals(32, takenOwner.size());
        assertSame(takeFirst, takenOwner.take());
        assertEquals(0, takenOwner.size());
        takenOwner.close();
        assertArrayEquals(takeSnapshot, takeFirst);
        assertIllegalState(takenOwner::take);

        byte[] closeFirst = sampleJpeg(32);
        FaceJpegEncoder.OwnedJpeg closedOwner = new FaceJpegEncoder.OwnedJpeg(closeFirst);
        closedOwner.close();
        assertEquals(0, closedOwner.size());
        assertAllZero(closeFirst);
        assertIllegalState(closedOwner::take);
        closedOwner.close();

        for (int attempt = 0; attempt < 100; attempt++) {
            byte[] bytes = sampleJpeg(32);
            byte[] snapshot = bytes.clone();
            FaceJpegEncoder.OwnedJpeg owned = new FaceJpegEncoder.OwnedJpeg(bytes);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<byte[]> taken = new AtomicReference<>();
            AtomicBoolean takeFailed = new AtomicBoolean();
            AtomicReference<Throwable> childFailure = new AtomicReference<>();
            Thread taker = checkedThread(childFailure, () -> {
                await(start);
                try { taken.set(owned.take()); }
                catch (IllegalStateException expected) { takeFailed.set(true); }
            });
            Thread closer = checkedThread(childFailure, () -> {
                await(start);
                owned.close();
            });
            taker.start();
            closer.start();
            start.countDown();
            taker.join(1000L);
            closer.join(1000L);
            assertFalse(taker.isAlive());
            assertFalse(closer.isAlive());
            rethrow(childFailure.get());
            assertEquals(0, owned.size());
            if (takeFailed.get()) {
                assertNull(taken.get());
                assertAllZero(bytes);
            } else {
                assertSame(bytes, taken.get());
                assertArrayEquals(snapshot, taken.get());
            }
            assertIllegalState(owned::take);
            owned.close();
        }
    }

    @Test
    public void deliveryWipesWhenCallbackDoesNotTakeOrThrows() {
        byte[] normal = sampleJpeg(16);
        FaceJpegEncoderDelivery.deliver(1L, normal, new FaceJpegEncoder.Callback() {
            @Override public void onEncoded(long frameId, FaceJpegEncoder.OwnedJpeg jpeg) {
                assertEquals(16, jpeg.size());
            }
            @Override public void onFailure(long frameId, String message, String code) { }
        });
        assertAllZero(normal);

        byte[] throwing = sampleJpeg(16);
        FaceJpegEncoderDelivery.deliver(2L, throwing, new FaceJpegEncoder.Callback() {
            @Override public void onEncoded(long frameId, FaceJpegEncoder.OwnedJpeg jpeg) {
                throw new LinkageError("test");
            }
            @Override public void onFailure(long frameId, String message, String code) { }
        });
        assertAllZero(throwing);

        byte[] taken = sampleJpeg(16);
        AtomicReference<byte[]> transfer = new AtomicReference<>();
        FaceJpegEncoderDelivery.deliver(3L, taken, new FaceJpegEncoder.Callback() {
            @Override public void onEncoded(long frameId, FaceJpegEncoder.OwnedJpeg jpeg) {
                transfer.set(jpeg.take());
            }
            @Override public void onFailure(long frameId, String message, String code) { }
        });
        assertSame(taken, transfer.get());
        assertFalse(allZero(taken));
    }

    @Test
    public void invalidFrameIdStillClosesAlreadyTransferredBorrow() {
        AtomicInteger releases = new AtomicInteger();
        byte[] bytes = new byte[6];
        Arrays.fill(bytes, (byte) 9);
        FaceFrame frame = new FaceFrame(7L, bytes, 2, 2, 0, 1, 0, false,
                ignored -> releases.incrementAndGet());
        FaceFrame.Borrow borrow = frame.borrow();
        frame.close();
        try {
            FaceJpegEncoderAdmission.validate(8L, borrow, new NoopCallback());
            fail("expected invalid frame id");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(1, releases.get());
        assertAllZero(bytes);
    }

    @Test
    public void everyAdmissionRejectionClosesBorrowBeforeOneFixedFailureCallback() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicBoolean sawCleanBorrow = new AtomicBoolean();
        byte[] bytes = new byte[6];
        Arrays.fill(bytes, (byte) 5);
        FaceFrame frame = new FaceFrame(11L, bytes, 2, 2, 0, 1, 0, false,
                ignored -> releases.incrementAndGet());
        FaceFrame.Borrow borrow = frame.borrow();
        frame.close();
        FaceJpegEncoderRejection.reject(11L, borrow, new FaceJpegEncoder.Callback() {
            @Override public void onEncoded(long frameId, FaceJpegEncoder.OwnedJpeg jpeg) { }
            @Override public void onFailure(long frameId, String message, String code) {
                failures.incrementAndGet();
                sawCleanBorrow.set(releases.get() == 1 && allZero(bytes));
                throw new LinkageError("test callback");
            }
        }, "ENCODER_BUSY");
        assertEquals(1, failures.get());
        assertTrue(sawCleanBorrow.get());
    }

    @Test
    public void queuedCancelSuppressesDeliveryAndWaitsForCleanup() throws Exception {
        FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();
        AtomicBoolean cancelReturned = new AtomicBoolean();
        Thread cancel = new Thread(() -> {
            gate.cancel();
            cancelReturned.set(true);
        });
        cancel.start();
        waitUntil(() -> gate.isCancellationRequested());
        waitUntil(() -> gate.waiterCountForTest() == 1);
        assertFalse(cancelReturned.get());
        assertEquals(FaceJpegEncoderJobGate.Start.CANCELLED, gate.tryStart());
        gate.completeCleanup();
        completeTerminal(gate);
        cancel.join(1000L);
        assertTrue(cancelReturned.get());
        assertFalse(gate.tryClaimDelivery());
    }

    @Test
    public void runningCancelWaitsUninterruptiblyForCleanupAndRestoresInterrupt()
            throws Exception {
        FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        Thread worker = checkedThread(childFailure, () -> {
            assertEquals(FaceJpegEncoderJobGate.Start.RUN, gate.tryStart());
            started.countDown();
            await(finish);
            gate.completeCleanup();
            completeTerminal(gate);
        });
        worker.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        AtomicBoolean restored = new AtomicBoolean();
        Thread cancel = new Thread(() -> {
            gate.cancel();
            restored.set(Thread.currentThread().isInterrupted());
        });
        cancel.start();
        waitUntil(() -> gate.waiterCountForTest() == 1);
        cancel.interrupt();
        assertTrue(cancel.isAlive());
        finish.countDown();
        worker.join(1000L);
        cancel.join(1000L);
        assertTrue(restored.get());
        assertFalse(gate.tryClaimDelivery());
        rethrow(childFailure.get());
    }

    @Test
    public void deliveryFirstMakesCancelWaitForCallbackButWorkerSelfCancelNeverWaits()
            throws Exception {
        FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackExit = new CountDownLatch(1);
        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        Thread worker = checkedThread(childFailure, () -> {
            assertEquals(FaceJpegEncoderJobGate.Start.RUN, gate.tryStart());
            gate.completeCleanup();
            assertTrue(gate.tryClaimDelivery());
            callbackEntered.countDown();
            await(callbackExit);
            gate.completeDelivery();
            completeTerminal(gate);
        });
        worker.start();
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread cancel = new Thread(() -> {
            gate.cancel();
            cancelled.set(true);
        });
        cancel.start();
        waitUntil(() -> gate.waiterCountForTest() == 1);
        assertFalse(cancelled.get());
        callbackExit.countDown();
        worker.join(1000L);
        cancel.join(1000L);
        assertTrue(cancelled.get());
        assertFalse(gate.tryClaimDelivery());

        FaceJpegEncoderJobGate self = new FaceJpegEncoderJobGate();
        Thread selfWorker = checkedThread(childFailure, () -> {
            assertEquals(FaceJpegEncoderJobGate.Start.RUN, self.tryStart());
            self.cancel();
            self.completeCleanup();
            completeTerminal(self);
        });
        selfWorker.start();
        selfWorker.join(1000L);
        assertFalse(selfWorker.isAlive());
        rethrow(childFailure.get());
    }

    @Test
    public void multipleCancelWaitersAllWaitForOneCleanupAndOneDeliveryClaim()
            throws Exception {
        FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();
        assertEquals(FaceJpegEncoderJobGate.Start.RUN, gate.tryStart());
        AtomicInteger returned = new AtomicInteger();
        Thread[] waiters = new Thread[3];
        for (int i = 0; i < waiters.length; i++) {
            waiters[i] = new Thread(() -> {
                gate.cancel();
                returned.incrementAndGet();
            });
            waiters[i].start();
        }
        waitUntil(() -> gate.waiterCountForTest() == 3);
        assertEquals(0, returned.get());
        gate.completeCleanup();
        completeTerminal(gate);
        for (Thread waiter : waiters) waiter.join(1000L);
        assertEquals(3, returned.get());
        assertFalse(gate.tryClaimDelivery());

        FaceJpegEncoderJobGate delivery = new FaceJpegEncoderJobGate();
        assertEquals(FaceJpegEncoderJobGate.Start.RUN, delivery.tryStart());
        delivery.completeCleanup();
        assertTrue(delivery.tryClaimDelivery());
        assertFalse(delivery.tryClaimDelivery());
        delivery.completeDelivery();
        completeTerminal(delivery);
    }

    @Test
    public void cancelAfterCleanupStillWaitsForFinalJpegWipeAndOwnerClear()
            throws Exception {
        assertNotNull(FaceJpegEncoderJobGate.class.getDeclaredMethod("completeTerminal"));
        FaceJpegEncoderJobGate gate = new FaceJpegEncoderJobGate();
        FaceJpegEncoderOwnerGate owner = new FaceJpegEncoderOwnerGate();
        Object job = new Object();
        assertEquals(FaceJpegEncoderOwnerGate.Admission.ACCEPTED, owner.tryAccept(job));
        assertEquals(FaceJpegEncoderJobGate.Start.RUN, gate.tryStart());

        byte[] finalBytes = sampleJpeg(32);
        FaceJpegEncoder.OwnedJpeg wrapper = new FaceJpegEncoder.OwnedJpeg(finalBytes);
        gate.completeCleanup();

        AtomicBoolean cancelReturned = new AtomicBoolean();
        Thread cancel = new Thread(() -> {
            gate.cancel();
            cancelReturned.set(true);
        });
        cancel.start();
        waitUntil(() -> gate.waiterCountForTest() == 1);
        assertFalse(cancelReturned.get());
        assertEquals(FaceJpegEncoderOwnerGate.Admission.BUSY,
                owner.tryAccept(new Object()));
        assertFalse(allZero(finalBytes));

        wrapper.close();
        assertAllZero(finalBytes);
        assertTrue(owner.clear(job));
        completeTerminal(gate);
        completeTerminal(gate);
        cancel.join(1000L);
        assertFalse(cancel.isAlive());
        assertTrue(cancelReturned.get());
        assertEquals(FaceJpegEncoderOwnerGate.Admission.ACCEPTED,
                owner.tryAccept(new Object()));
    }

    @Test
    public void ownerAdmissionIsSingleFlightReusableAfterCallbackAndClosedForever() {
        FaceJpegEncoderOwnerGate owner = new FaceJpegEncoderOwnerGate();
        Object first = new Object();
        Object second = new Object();
        assertEquals(FaceJpegEncoderOwnerGate.Admission.ACCEPTED,
                owner.tryAccept(first));
        assertEquals(FaceJpegEncoderOwnerGate.Admission.BUSY,
                owner.tryAccept(second));
        assertFalse(owner.clear(second));
        byte[] callbackBytes = sampleJpeg(8);
        FaceJpegEncoderJobGate completionGate = new FaceJpegEncoderJobGate();
        assertEquals(FaceJpegEncoderJobGate.Start.RUN, completionGate.tryStart());
        completionGate.completeCleanup();
        assertTrue(completionGate.tryClaimDelivery());
        FaceJpegEncoderCompletion.deliverAndClear(owner, first, completionGate,
                1L, new FaceJpegEncoder.OwnedJpeg(callbackBytes),
                new FaceJpegEncoder.Callback() {
                    @Override public void onEncoded(long id, FaceJpegEncoder.OwnedJpeg jpeg) {
                        throw new RuntimeException("test callback");
                    }
                    @Override public void onFailure(long id, String message, String code) { }
                });
        assertAllZero(callbackBytes);
        assertEquals(FaceJpegEncoderOwnerGate.Admission.ACCEPTED,
                owner.tryAccept(second));
        assertSame(second, owner.closeAndTakeActive());
        assertTrue(owner.isClosed());
        assertEquals(FaceJpegEncoderOwnerGate.Admission.CLOSED,
                owner.tryAccept(new Object()));
        assertNull(owner.closeAndTakeActive());
    }

    @Test
    public void boundedStreamGrowsOnlyToCapAndWipesEveryBackingArray() throws Exception {
        WipingBoundedOutputStream stream = new WipingBoundedOutputStream(4, 16);
        byte[] originalBacking = stream.backingForTest();
        stream.write(new byte[] {1, 2, 3, 4, 5, 6});
        assertAllZero(originalBacking);
        byte[] grownBacking = stream.backingForTest();
        byte[] copy = stream.toByteArray();
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, copy);
        try {
            stream.write(new byte[11]);
            fail("expected bounded failure");
        } catch (IOException expected) {
            // expected
        }
        stream.close();
        assertAllZero(grownBacking);
        stream.close();
        assertIoFailure(() -> stream.write(1));
        assertIoFailure(stream::toByteArray);

        WipingBoundedOutputStream exact = new WipingBoundedOutputStream(4, 16);
        exact.write(new byte[16]);
        assertEquals(16, exact.size());
        assertIoFailure(() -> exact.write(1));
        assertIoFailure(() -> exact.write(new byte[1], 0, 1));
        try {
            exact.write(new byte[2], 1, 2);
            fail("expected offset/length rejection");
        } catch (IndexOutOfBoundsException expected) { }
        try {
            exact.write(null, 0, 0);
            fail("expected null rejection");
        } catch (NullPointerException expected) { }
        exact.close();

        assertEquals(16, WipingBoundedOutputStream.checkedRequired(15, 1, 16));
        assertIoFailure(() -> WipingBoundedOutputStream.checkedRequired(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    public void pureJpegPolicyChecksEnvelopeCapAndRotatedDimensions() {
        assertTrue(JpegValidationPolicy.hasValidEnvelope(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9
        }));
        byte[] valid = sampleJpeg(8);
        assertTrue(JpegValidationPolicy.hasValidEnvelope(valid));
        assertFalse(JpegValidationPolicy.hasValidEnvelope(null));
        assertFalse(JpegValidationPolicy.hasValidEnvelope(new byte[3]));
        valid[0] = 0;
        assertFalse(JpegValidationPolicy.hasValidEnvelope(valid));
        byte[] maximum = new byte[JpegValidationPolicy.MAX_BYTES];
        maximum[0] = (byte) 0xff;
        maximum[1] = (byte) 0xd8;
        maximum[maximum.length - 2] = (byte) 0xff;
        maximum[maximum.length - 1] = (byte) 0xd9;
        assertTrue(JpegValidationPolicy.hasValidEnvelope(maximum));
        byte[] oversized = Arrays.copyOf(maximum, maximum.length + 1);
        oversized[oversized.length - 2] = (byte) 0xff;
        oversized[oversized.length - 1] = (byte) 0xd9;
        assertFalse(JpegValidationPolicy.hasValidEnvelope(oversized));
        maximum[maximum.length - 1] = 0;
        assertFalse(JpegValidationPolicy.hasValidEnvelope(maximum));
        assertEquals(640, JpegValidationPolicy.expectedWidth(0));
        assertEquals(480, JpegValidationPolicy.expectedHeight(0));
        assertEquals(480, JpegValidationPolicy.expectedWidth(90));
        assertEquals(640, JpegValidationPolicy.expectedHeight(90));
        assertEquals(640, JpegValidationPolicy.expectedWidth(180));
        assertEquals(480, JpegValidationPolicy.expectedHeight(180));
        assertEquals(480, JpegValidationPolicy.expectedWidth(270));
        assertEquals(640, JpegValidationPolicy.expectedHeight(270));
        assertIllegalArgument(() -> JpegValidationPolicy.expectedWidth(45));
        assertIllegalArgument(() -> JpegValidationPolicy.expectedHeight(45));
    }

    @Test
    public void androidPipelineIsFullFrameBoundedValidatedAndCleanupFirst() throws Exception {
        String source = encoder();
        assertContains(source, "new YuvImage(");
        assertContains(source, "ImageFormat.NV21");
        assertContains(source, "new Rect(0, 0, WIDTH, HEIGHT)");
        assertContains(source, "JPEG_QUALITY = 85");
        assertTrue(Pattern.compile("compressToJpeg\\s*\\([^;]*JPEG_QUALITY[^;]*\\)",
                Pattern.DOTALL).matcher(source).find());
        assertTrue(Pattern.compile("rotated\\.compress\\s*\\([^;]*JPEG_QUALITY[^;]*\\)",
                Pattern.DOTALL).matcher(source).find());
        assertContains(source, "BitmapFactory.decodeByteArray");
        assertTrue(occurrences(source, "BitmapFactory.decodeByteArray") >= 2);
        assertContains(source, "new Matrix()");
        assertContains(source, "matrix.postRotate(rotation)");
        assertContains(source, "Bitmap.createBitmap(intermediate, 0, 0,");
        assertContains(source, "Bitmap.createBitmap(intermediate, 0, 0, WIDTH, HEIGHT");
        assertFalse(source.contains("postScale(-1"));
        assertFalse(source.contains("preScale(-1"));
        assertFalse(source.contains("setScale("));
        assertFalse(source.contains("postScale("));
        assertFalse(source.contains("preScale("));
        assertContains(source, "WipingBoundedOutputStream");
        assertEquals(2, occurrences(source, "new WipingBoundedOutputStream(MAX_BYTES)"));
        assertContains(source, "MAX_BYTES = 1048576");
        assertContains(source, "JpegValidationPolicy.hasValidEnvelope");
        assertContains(source, "expectedWidth(rotation)");
        assertContains(source, "expectedHeight(rotation)");
        assertContains(source, "recycleDistinct");
        assertContains(source, "ownedFrame.close()");
        assertContains(source, "gate.completeCleanup()");
        assertTrue(source.indexOf("gate.completeCleanup()")
                < source.indexOf("gate.tryClaimDelivery()"));
        assertContains(source, "FaceJpegEncoderDelivery.deliver");
        assertContains(source, "ownedFrame.width() != WIDTH");
        assertContains(source, "ownedFrame.height() != HEIGHT");
        assertContains(source, "ownedFrame.nv21().length != NV21_BYTES");
        assertContains(source, "ownedFrame.jpegMirror()");
        assertTrue(occurrences(source, "requireNotCancelled()") >= 7);
        String worker = methodSlice(source, "public void run()", "private byte[] encodeFullFrame()");
        assertOrdered(worker, "encodeFullFrame()", "gate.tryClaimDelivery()");
        String pipeline = methodSlice(source, "private byte[] encodeFullFrame()",
                "private void requireNotCancelled()");
        assertOrdered(pipeline, "BitmapFactory.decodeByteArray(",
                "validation.getWidth()", "validation.getHeight()",
                "successBytes = finalBytes");
        assertOrdered(pipeline, "ownedFrame.close()", "recycleDistinct(",
                "wipe(intermediateBytes)", "wipe(finalBytes)",
                "closeStream(intermediateOutput)", "closeStream(finalOutput)",
                "gate.completeCleanup()");
        assertTrue(Pattern.compile(
                "finally\\s*\\{\\s*gate\\.completeCleanup\\s*\\(\\s*\\)\\s*;")
                .matcher(pipeline).find());
        String recycle = methodSlice(source, "private static void recycleOne(",
                "private static void closeStream(");
        assertContains(recycle, "OutOfMemoryError");
        String closeStream = methodSlice(source, "private static void closeStream(",
                "private static void wipe(");
        assertContains(closeStream, "OutOfMemoryError");
        String wipe = methodSlice(source, "private static void wipe(", "\n}");
        assertContains(wipe, "OutOfMemoryError");
    }

    @Test
    public void actualEncoderWiresAdmissionOwnerJobDeliveryRejectAndCloseSeams()
            throws Exception {
        String source = encoder();
        String encode = methodSlice(source,
                "public FaceCaptureSession.Cancellable encode(", "public void close()");
        assertOrdered(encode, "FaceJpegEncoderAdmission.validate(",
                "ownerGate.tryAccept(job)");
        assertContains(encode, "FaceJpegEncoderOwnerGate.Admission.BUSY");
        assertContains(encode, "FaceJpegEncoderOwnerGate.Admission.CLOSED");
        assertContains(encode, "FaceJpegEncoderRejection.reject(");
        assertContains(encode, "executor.execute(job)");
        assertTrue(encode.lastIndexOf("FaceJpegEncoderRejection.reject(")
                > encode.indexOf("executor.execute(job)"));

        String run = methodSlice(source, "public void run()",
                "private byte[] encodeFullFrame()");
        assertContains(run, "gate.tryStart()");
        assertContains(run, "gate.tryClaimDelivery()");
        assertContains(run, "FaceJpegEncoderCompletion.deliverAndClear(");
        assertFalse(run.substring(0,
                run.indexOf("FaceJpegEncoderCompletion.deliverAndClear("))
                .contains("ownerGate.clear(this)"));

        String close = methodSlice(source, "public void close()",
                "private void awaitTerminationUninterruptibly()");
        assertOrdered(close, "ownerGate.closeAndTakeActive()",
                "if (claimed != null) claimed.cancel()", "executor.shutdown()");
        assertContains(close, "if (Thread.currentThread() != workerThread)");
        assertContains(close, "awaitTerminationUninterruptibly()");

        String contract = faceContract();
        String rejection = methodSlice(contract, "static void reject(",
                "final class FaceJpegEncoderDelivery");
        assertOrdered(rejection, "closeBorrowQuietly(ownedFrame)",
                "callback.onFailure(frameId, SAFE_MESSAGE, diagnosticCode)");
        String completion = methodSlice(contract, "static void deliverAndClear(",
                "final class FaceJpegEncoderOwnerGate");
        assertOrdered(completion, "FaceJpegEncoderDelivery.deliver(",
                "gate.completeDelivery()", "ownerGate.clear(job)",
                "gate.completeTerminal()");
        String jobGate = methodSlice(contract, "final class FaceJpegEncoderJobGate",
                "final class WipingBoundedOutputStream");
        assertContains(jobGate, "private boolean terminalComplete");
        assertContains(jobGate, "while (!terminalComplete)");
        assertContains(run, "gate.completeTerminal()");
    }

    @Test
    public void encoderCancellationCloseAndFailuresAreFixedNoThrowBoundaries()
            throws Exception {
        String source = encoder();
        assertContains(source, "ENCODER_BUSY");
        assertContains(source, "ENCODER_CLOSED");
        assertContains(source, "ENCODER_SCHEDULING_FAILED");
        assertContains(source, "JPEG_ENCODING_FAILED");
        assertContains(source, "catch (RuntimeException | LinkageError");
        assertContains(source, "OutOfMemoryError");
        assertContains(source, "awaitTerminationUninterruptibly");
        assertContains(source, "Thread.currentThread().interrupt()");
        assertContains(source, "if (Thread.currentThread() != workerThread)");
        assertContains(source, "if (ownerGate.isClosed())");
        assertContains(source, "ownerGate.closeAndTakeActive()");
        assertContains(source, "executor.shutdown()");
        assertContains(source, "if (claimed != null) claimed.cancel()");
        assertFalse(source.contains("getMessage()"));
        assertFalse(source.contains("printStackTrace"));
        assertFalse(source.contains("android.util.Log"));
        assertFalse(source.contains("System.out"));
        assertFalse(source.contains("System.err"));
        assertFalse(source.contains("Base64"));
        assertFalse(source.contains("java.io.File"));
        assertFalse(source.contains("FileOutputStream"));
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

    private static final class NoopCallback implements FaceJpegEncoder.Callback {
        @Override public void onEncoded(long frameId, FaceJpegEncoder.OwnedJpeg jpeg) { }
        @Override public void onFailure(long frameId, String message, String code) { }
    }

    private static byte[] sampleJpeg(int size) {
        byte[] result = new byte[size];
        Arrays.fill(result, (byte) 7);
        result[0] = (byte) 0xff;
        result[1] = (byte) 0xd8;
        result[size - 2] = (byte) 0xff;
        result[size - 1] = (byte) 0xd9;
        return result;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static void assertAllZero(byte[] bytes) {
        assertTrue("expected wiped bytes", allZero(bytes));
    }

    private static void await(CountDownLatch latch) {
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

    private static void waitUntil(Check condition) throws Exception {
        for (int i = 0; i < 100000 && !condition.ok(); i++) Thread.yield();
        assertTrue(condition.ok());
    }

    private static void completeTerminal(FaceJpegEncoderJobGate gate) {
        try {
            FaceJpegEncoderJobGate.class.getDeclaredMethod("completeTerminal").invoke(gate);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private interface Check { boolean ok(); }

    private static void assertIllegalState(Runnable action) {
        try { action.run(); fail("expected IllegalStateException"); }
        catch (IllegalStateException expected) { }
    }

    private static void assertIllegalArgument(Runnable action) {
        try { action.run(); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void assertIoFailure(IoRunnable action) {
        try { action.run(); fail("expected IOException"); }
        catch (IOException expected) { }
    }

    private interface IoRunnable { void run() throws IOException; }

    private static String faceContract() throws IOException {
        return read("app/src/main/java/com/codex/lockertest/face/FaceJpegEncoder.java");
    }

    private static String encoder() throws IOException {
        return read("app/src/main/java/com/codex/lockertest/face/AndroidFaceJpegEncoder.java");
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

    private static String methodSlice(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue("missing " + startToken, start >= 0);
        assertTrue("missing " + endToken, end > start);
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token, previous + 1);
            assertTrue("missing/out-of-order token: " + token, current > previous);
            previous = current;
        }
    }

    private static Thread checkedThread(AtomicReference<Throwable> failure,
            Runnable operation) {
        return new Thread(() -> {
            try { operation.run(); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
    }

    private static void rethrow(Throwable failure) {
        if (failure != null) throw new AssertionError(failure);
    }
}
