package com.codex.lockertest.face;

import com.codex.lockertest.face.verification.FaceJpegContract;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LatestFaceFrameMailboxTest {
    @Test
    public void frameValidatesShapeDimensionsTransformsAndReleaser() {
        byte[] bytes = nv21();
        FaceFrame frame = new FaceFrame(1L, bytes, 2, 2, 90, 1, 270, true, ignored -> { });
        FaceFrame.Borrow borrow = frame.borrow();
        assertSame(bytes, borrow.nv21());
        assertEquals(1L, borrow.frameId());
        assertEquals(2, borrow.width());
        assertEquals(2, borrow.height());
        assertEquals(90, borrow.sdkRotationDegrees());
        assertEquals(1, borrow.sdkMirror());
        assertEquals(270, borrow.jpegRotationDegrees());
        assertTrue(borrow.jpegMirror());
        borrow.close();
        frame.close();

        assertIllegal(() -> new FaceFrame(0, nv21(), 2, 2, 0, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 1, 2, 0, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 2, 1, 0, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, new byte[5], 2, 2, 0, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 2, 2, 45, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 2, 2, 0, 2, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 2, 2, 0, 0, 45, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, null, 2, 2, 0, 0, 0, false, ignored -> { }));
        assertIllegal(() -> new FaceFrame(1, nv21(), 2, 2, 0, 0, 0, false, null));
        assertIllegal(() -> new FaceFrame(1, new byte[6], Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE - 1, 0, 0, 0, false, ignored -> { }));
    }

    @Test
    public void ownerCloseDefersZeroUntilExclusiveBorrowClosesAndReleasesOnce() {
        byte[] bytes = nv21();
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<byte[]> released = new AtomicReference<>();
        FaceFrame frame = new FaceFrame(7, bytes, 2, 2, 0, 1, 90, false, zeroed -> {
            releases.incrementAndGet();
            released.set(zeroed);
        });
        FaceFrame.Borrow borrow = frame.borrow();
        assertIllegal(frame::borrow);
        frame.close();
        frame.close();
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, bytes);
        assertIllegal(frame::borrow);
        borrow.close();
        borrow.close();
        assertArrayEquals(new byte[6], bytes);
        assertSame(bytes, released.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void closedFrameBorrowCannotReExposeBytesDuringANewerBorrow() {
        FaceFrame frame = new FaceFrame(7, nv21(), 2, 2, 0, 1, 90, false,
                ignored -> { });
        FaceFrame.Borrow oldBorrow = frame.borrow();
        oldBorrow.close();
        FaceFrame.Borrow currentBorrow = frame.borrow();
        assertIllegal(oldBorrow::nv21);
        assertNotNull(currentBorrow.nv21());
        currentBorrow.close();
        frame.close();
    }

    @Test
    public void frameCallsReleaserOutsideMonitorAndIsolatesRuntimeException() {
        byte[] bytes = nv21();
        AtomicReference<FaceFrame> reference = new AtomicReference<>();
        AtomicBoolean held = new AtomicBoolean(true);
        FaceFrame frame = new FaceFrame(1, bytes, 2, 2, 0, 0, 0, false, zeroed -> {
            held.set(Thread.holdsLock(reference.get()));
            throw new IllegalStateException("test");
        });
        reference.set(frame);
        frame.close();
        assertFalse(held.get());
        assertArrayEquals(new byte[6], bytes);
        frame.close();
    }

    @Test(expected = AssertionError.class)
    public void frameDoesNotSwallowReleaserError() {
        new FaceFrame(1, nv21(), 2, 2, 0, 0, 0, false, zeroed -> {
            throw new AssertionError("must propagate");
        }).close();
    }

    @Test
    public void mailboxReplacesTakesClosesAndOwnsRejectedOffers() {
        AtomicInteger releases = new AtomicInteger();
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        FaceFrame first = frame(1, releases, null);
        FaceFrame second = frame(2, releases, null);
        assertTrue(mailbox.offer(first));
        assertTrue(mailbox.offer(second));
        assertEquals(1, releases.get());
        assertSame(second, mailbox.take());
        assertNull(mailbox.take());
        second.close();
        assertEquals(2, releases.get());
        FaceFrame third = frame(3, releases, null);
        assertTrue(mailbox.offer(third));
        mailbox.close();
        assertEquals(3, releases.get());
        FaceFrame rejected = frame(4, releases, null);
        assertFalse(mailbox.offer(rejected));
        assertEquals(4, releases.get());
        mailbox.close();
    }

    @Test
    public void delayedLowerFrameCannotOverwriteAnAlreadyOfferedHigherFrame() {
        AtomicInteger releases = new AtomicInteger();
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        FaceFrame higher = frame(3, releases, null);
        FaceFrame delayedLower = frame(2, releases, null);
        assertTrue(mailbox.offer(higher));
        assertFalse(mailbox.offer(delayedLower));
        assertEquals(1, releases.get());
        assertSame(higher, mailbox.take());
        higher.close();
        mailbox.close();
        assertEquals(2, releases.get());
    }

    @Test
    public void takeRetainsHighWaterAndRejectsALowerFrameThatArrivesLater() {
        AtomicInteger releases = new AtomicInteger();
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        FaceFrame higher = frame(3, releases, null);
        assertTrue(mailbox.offer(higher));
        assertSame(higher, mailbox.take());
        FaceFrame delayedLower = frame(2, releases, null);
        assertFalse(mailbox.offer(delayedLower));
        assertEquals(1, releases.get());
        assertNull(mailbox.take());
        higher.close();
        mailbox.close();
        assertEquals(2, releases.get());
    }

    @Test
    public void mailboxNeverClosesFrameUnderMailboxLock() {
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        AtomicBoolean held = new AtomicBoolean(true);
        mailbox.offer(frame(1, new AtomicInteger(), () -> held.set(Thread.holdsLock(mailbox))));
        mailbox.offer(frame(2, new AtomicInteger(), null));
        assertFalse(held.get());
        mailbox.close();
    }

    @Test
    public void blockedReplacementReleaseDoesNotBlockTake() throws Exception {
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        CountDownLatch enteredRelease = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        mailbox.offer(frame(1, new AtomicInteger(), () -> {
            enteredRelease.countDown();
            await(allowRelease);
        }));
        FaceFrame newer = frame(2, new AtomicInteger(), null);
        Thread replacer = new Thread(() -> mailbox.offer(newer));
        replacer.start();
        assertTrue(enteredRelease.await(1, TimeUnit.SECONDS));
        assertSame(newer, mailbox.take());
        allowRelease.countDown();
        replacer.join(1000);
        assertFalse(replacer.isAlive());
        newer.close();
        mailbox.close();
    }

    @Test
    public void blockedClosedOfferReleaseDoesNotHoldMailboxMonitor() throws Exception {
        LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
        mailbox.close();
        CountDownLatch enteredRelease = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        Thread offer = new Thread(() -> mailbox.offer(frame(1, new AtomicInteger(), () -> {
            enteredRelease.countDown();
            await(allowRelease);
        })));
        offer.start();
        assertTrue(enteredRelease.await(1, TimeUnit.SECONDS));
        assertNull(mailbox.take());
        allowRelease.countDown();
        offer.join(1000);
        assertFalse(offer.isAlive());
    }

    @Test
    public void concurrentOfferAndCloseReleaseTheOfferedFrameExactlyOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
            AtomicInteger releases = new AtomicInteger();
            FaceFrame offered = frame(1, releases, null);
            CountDownLatch start = new CountDownLatch(1);
            Thread offer = new Thread(() -> { await(start); mailbox.offer(offered); });
            Thread close = new Thread(() -> { await(start); mailbox.close(); });
            offer.start();
            close.start();
            start.countDown();
            offer.join(1000);
            close.join(1000);
            assertFalse(offer.isAlive());
            assertFalse(close.isAlive());
            mailbox.close();
            assertEquals(1, releases.get());
        }
    }

    @Test
    public void concurrentTakeAndReplaceRemainLinearizableAndReleaseBothOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            LatestFaceFrameMailbox mailbox = new LatestFaceFrameMailbox();
            AtomicInteger releases = new AtomicInteger();
            FaceFrame first = frame(1, releases, null);
            FaceFrame second = frame(2, releases, null);
            assertTrue(mailbox.offer(first));
            AtomicReference<FaceFrame> taken = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread take = new Thread(() -> { await(start); taken.set(mailbox.take()); });
            Thread replace = new Thread(() -> { await(start); mailbox.offer(second); });
            take.start();
            replace.start();
            start.countDown();
            take.join(1000);
            replace.join(1000);
            assertFalse(take.isAlive());
            assertFalse(replace.isAlive());
            FaceFrame transferred = taken.get();
            if (transferred != null) transferred.close();
            FaceFrame pending = mailbox.take();
            if (pending != null) pending.close();
            mailbox.close();
            assertEquals(2, releases.get());
        }
    }

    @Test
    public void captureKeepsSelectedFrameAndUsesExactRequestId() {
        AtomicInteger releases = new AtomicInteger();
        FaceFrame selected = frame(9, releases, null);
        FaceCapture capture = new FaceCapture(23, selected);
        assertEquals(9L, capture.frameId());
        assertEquals("face-23-9", capture.requestId());
        FaceFrame.Borrow borrow = capture.borrowFrame();
        assertEquals(9L, borrow.frameId());
        assertEquals(90, borrow.jpegRotationDegrees());
        borrow.close();
        capture.close();
        assertEquals(1, releases.get());
    }

    @Test
    public void captureValidatesOwnsAndDefersJpegZeroing() {
        FaceCapture capture = new FaceCapture(1, frame(1, new AtomicInteger(), null));
        byte[] valid = jpeg(8);
        assertTrue(capture.acceptOwnedJpeg(valid));
        byte[] duplicate = jpeg(8);
        assertFalse(capture.acceptOwnedJpeg(duplicate));
        assertArrayEquals(new byte[8], duplicate);
        FaceCapture.JpegBorrow borrow = capture.borrowJpeg();
        assertSame(valid, borrow.jpeg());
        assertIllegal(capture::borrowJpeg);
        capture.close();
        assertFalse(allZero(valid));
        borrow.close();
        borrow.close();
        assertArrayEquals(new byte[8], valid);
        assertIllegal(capture::borrowJpeg);
    }

    @Test
    public void closedJpegBorrowCannotReExposeBytesDuringANewerBorrow() {
        FaceCapture capture = new FaceCapture(1, frame(1, new AtomicInteger(), null));
        assertTrue(capture.acceptOwnedJpeg(jpeg(8)));
        FaceCapture.JpegBorrow oldBorrow = capture.borrowJpeg();
        oldBorrow.close();
        FaceCapture.JpegBorrow currentBorrow = capture.borrowJpeg();
        assertIllegal(oldBorrow::jpeg);
        assertNotNull(currentBorrow.jpeg());
        currentBorrow.close();
        capture.close();
    }

    @Test
    public void captureRejectsAndZeroesMalformedOrOversizedJpegs() {
        FaceCapture capture = new FaceCapture(1, frame(1, new AtomicInteger(), null));
        byte[][] invalid = {
                new byte[0], new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
                new byte[] {0, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9},
                new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 0, (byte) 0xd9},
                new byte[FaceJpegContract.MAX_BYTES + 1]
        };
        for (byte[] bytes : invalid) {
            assertFalse(capture.acceptOwnedJpeg(bytes));
            assertTrue(allZero(bytes));
        }
        assertFalse(capture.acceptOwnedJpeg(null));
        capture.close();
    }

    private static FaceFrame frame(long id, AtomicInteger releases, Runnable onRelease) {
        return new FaceFrame(id, nv21(), 2, 2, 0, 1, 90, false, zeroed -> {
            releases.incrementAndGet();
            if (onRelease != null) onRelease.run();
        });
    }

    private static byte[] nv21() {
        return new byte[] {1, 2, 3, 4, 5, 6};
    }

    private static byte[] jpeg(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        bytes[length - 2] = (byte) 0xff;
        bytes[length - 1] = (byte) 0xd9;
        return bytes;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException or IllegalStateException");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // expected
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
