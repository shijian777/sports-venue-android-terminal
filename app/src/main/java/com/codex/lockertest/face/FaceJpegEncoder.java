package com.codex.lockertest.face;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public interface FaceJpegEncoder extends AutoCloseable {
    final class OwnedJpeg implements AutoCloseable {
        private byte[] ownedBytes;
        private boolean terminal;

        OwnedJpeg(byte[] ownedBytes) {
            if (ownedBytes == null) throw new IllegalArgumentException("owned JPEG cannot be null");
            this.ownedBytes = ownedBytes;
        }

        public synchronized int size() {
            return terminal || ownedBytes == null ? 0 : ownedBytes.length;
        }

        public synchronized byte[] take() {
            if (terminal || ownedBytes == null) {
                throw new IllegalStateException("JPEG ownership is already terminal");
            }
            byte[] result = ownedBytes;
            ownedBytes = null;
            terminal = true;
            return result;
        }

        @Override public void close() {
            byte[] wipe;
            synchronized (this) {
                if (terminal) return;
                terminal = true;
                wipe = ownedBytes;
                ownedBytes = null;
            }
            if (wipe != null) Arrays.fill(wipe, (byte) 0);
        }
    }

    interface Callback {
        void onEncoded(long frameId, OwnedJpeg ownedJpeg);
        void onFailure(long frameId, String safeMessage, String diagnosticCode);
    }

    FaceCaptureSession.Cancellable encode(
            long frameId, FaceFrame.Borrow ownedFrame, Callback callback);
    @Override void close();
}

final class FaceJpegEncoderAdmission {
    private FaceJpegEncoderAdmission() { }

    static void validate(long frameId, FaceFrame.Borrow ownedFrame,
            FaceJpegEncoder.Callback callback) {
        if (ownedFrame == null) throw new IllegalArgumentException("owned frame cannot be null");
        if (callback == null || frameId <= 0L || frameId != ownedFrame.frameId()) {
            closeBorrowQuietly(ownedFrame);
            throw new IllegalArgumentException("frame and callback must be valid");
        }
    }

    static void closeBorrowQuietly(FaceFrame.Borrow ownedFrame) {
        if (ownedFrame == null) return;
        try { ownedFrame.close(); }
        catch (RuntimeException | LinkageError ignored) { }
    }
}

final class FaceJpegEncoderRejection {
    private static final String SAFE_MESSAGE = "人脸照片处理失败，请重新尝试";
    private FaceJpegEncoderRejection() { }

    static void reject(long frameId, FaceFrame.Borrow ownedFrame,
            FaceJpegEncoder.Callback callback, String diagnosticCode) {
        FaceJpegEncoderAdmission.closeBorrowQuietly(ownedFrame);
        try { callback.onFailure(frameId, SAFE_MESSAGE, diagnosticCode); }
        catch (RuntimeException | LinkageError ignored) { }
    }
}

final class FaceJpegEncoderDelivery {
    private FaceJpegEncoderDelivery() { }

    static void deliver(long frameId, byte[] ownedBytes, FaceJpegEncoder.Callback callback) {
        deliver(frameId, new FaceJpegEncoder.OwnedJpeg(ownedBytes), callback);
    }

    static void deliver(long frameId, FaceJpegEncoder.OwnedJpeg wrapper,
            FaceJpegEncoder.Callback callback) {
        try { callback.onEncoded(frameId, wrapper); }
        catch (RuntimeException | LinkageError ignored) { }
        finally { wrapper.close(); }
    }
}

final class FaceJpegEncoderCompletion {
    private FaceJpegEncoderCompletion() { }

    static void deliverAndClear(FaceJpegEncoderOwnerGate ownerGate, Object job,
            FaceJpegEncoderJobGate gate, long frameId,
            FaceJpegEncoder.OwnedJpeg wrapper, FaceJpegEncoder.Callback callback) {
        try {
            FaceJpegEncoderDelivery.deliver(frameId, wrapper, callback);
        } finally {
            try {
                gate.completeDelivery();
            } finally {
                try { ownerGate.clear(job); }
                finally { gate.completeTerminal(); }
            }
        }
    }

    static void failAndClear(FaceJpegEncoderOwnerGate ownerGate, Object job,
            FaceJpegEncoderJobGate gate, long frameId,
            FaceJpegEncoder.Callback callback, String safeMessage, String diagnosticCode) {
        try { callback.onFailure(frameId, safeMessage, diagnosticCode); }
        catch (RuntimeException | LinkageError ignored) { }
        finally {
            try {
                gate.completeDelivery();
            } finally {
                try { ownerGate.clear(job); }
                finally { gate.completeTerminal(); }
            }
        }
    }
}

final class FaceJpegEncoderOwnerGate {
    enum Admission { ACCEPTED, BUSY, CLOSED }
    private Object active;
    private boolean closed;

    synchronized Admission tryAccept(Object job) {
        if (job == null) throw new IllegalArgumentException("job cannot be null");
        if (closed) return Admission.CLOSED;
        if (active != null) return Admission.BUSY;
        active = job;
        return Admission.ACCEPTED;
    }

    synchronized boolean clear(Object job) {
        if (job == null || active != job) return false;
        active = null;
        return true;
    }

    synchronized Object closeAndTakeActive() {
        if (closed) return null;
        closed = true;
        Object result = active;
        active = null;
        return result;
    }

    synchronized boolean isClosed() { return closed; }
}

final class FaceJpegEncoderJobGate {
    enum Start { RUN, CANCELLED }
    private boolean started;
    private boolean cancellationRequested;
    private boolean cleanupComplete;
    private boolean deliveryClaimed;
    private boolean deliveryComplete;
    private boolean terminalComplete;
    private Thread workerThread;
    private int waiterCount;

    synchronized Start tryStart() {
        if (started) return Start.CANCELLED;
        started = true;
        workerThread = Thread.currentThread();
        return cancellationRequested ? Start.CANCELLED : Start.RUN;
    }

    synchronized boolean isCancellationRequested() { return cancellationRequested; }

    synchronized void completeCleanup() {
        cleanupComplete = true;
        notifyAll();
    }

    synchronized boolean tryClaimDelivery() {
        if (!cleanupComplete || cancellationRequested || deliveryClaimed) return false;
        deliveryClaimed = true;
        return true;
    }

    synchronized void completeDelivery() {
        if (deliveryClaimed) deliveryComplete = true;
        notifyAll();
    }

    synchronized void completeTerminal() {
        if (!cleanupComplete || (deliveryClaimed && !deliveryComplete)) return;
        terminalComplete = true;
        notifyAll();
    }

    void cancel() {
        Thread worker;
        synchronized (this) {
            cancellationRequested = true;
            worker = workerThread;
            notifyAll();
            if (Thread.currentThread() == worker) return;
        }
        awaitTerminalUninterruptibly();
    }

    void awaitCleanupUninterruptibly() {
        boolean interrupted = false;
        synchronized (this) {
            waiterCount++;
            try {
                while (!cleanupComplete) {
                    try { wait(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally { waiterCount--; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized int waiterCountForTest() { return waiterCount; }

    private void awaitTerminalUninterruptibly() {
        boolean interrupted = false;
        synchronized (this) {
            waiterCount++;
            try {
                while (!terminalComplete) {
                    try { wait(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally { waiterCount--; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}

final class WipingBoundedOutputStream extends OutputStream {
    private final int maximum;
    private byte[] buffer;
    private int count;
    private boolean closed;

    WipingBoundedOutputStream(int maximum) { this(4096, maximum); }

    WipingBoundedOutputStream(int initialCapacity, int maximum) {
        if (initialCapacity <= 0 || maximum <= 0 || initialCapacity > maximum) {
            throw new IllegalArgumentException("invalid bounded stream capacity");
        }
        this.maximum = maximum;
        this.buffer = new byte[initialCapacity];
    }

    @Override public void write(int value) throws IOException {
        ensureOpen();
        ensureCapacity(1);
        buffer[count++] = (byte) value;
    }

    @Override public void write(byte[] source, int offset, int length) throws IOException {
        if (source == null) throw new NullPointerException("source");
        if ((offset | length) < 0 || length > source.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        ensureOpen();
        ensureCapacity(length);
        System.arraycopy(source, offset, buffer, count, length);
        count += length;
    }

    byte[] toByteArray() throws IOException {
        ensureOpen();
        return Arrays.copyOf(buffer, count);
    }

    int size() { return count; }
    byte[] backingForTest() { return buffer; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        byte[] wipe = buffer;
        buffer = null;
        count = 0;
        if (wipe != null) Arrays.fill(wipe, (byte) 0);
    }

    static int checkedRequired(int current, int additional, int maximum)
            throws IOException {
        if (current < 0 || additional < 0 || maximum < 0) {
            throw new IOException("invalid bounded stream size");
        }
        long required = (long) current + (long) additional;
        if (required > maximum || required > Integer.MAX_VALUE) {
            throw new IOException("bounded stream limit exceeded");
        }
        return (int) required;
    }

    private void ensureOpen() throws IOException {
        if (closed || buffer == null) throw new IOException("stream is closed");
    }

    private void ensureCapacity(int additional) throws IOException {
        int required = checkedRequired(count, additional, maximum);
        if (required <= buffer.length) return;
        int grown = buffer.length;
        while (grown < required) {
            long doubled = (long) grown * 2L;
            grown = (int) Math.min((long) maximum, doubled);
            if (grown < required && grown == maximum) {
                throw new IOException("bounded stream limit exceeded");
            }
        }
        byte[] previous = buffer;
        byte[] replacement = new byte[grown];
        System.arraycopy(previous, 0, replacement, 0, count);
        buffer = replacement;
        Arrays.fill(previous, (byte) 0);
    }
}

final class JpegValidationPolicy {
    static final int MAX_BYTES = 1048576;
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private JpegValidationPolicy() { }

    static boolean hasValidEnvelope(byte[] jpeg) {
        return jpeg != null && jpeg.length >= 4 && jpeg.length <= MAX_BYTES
                && (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8
                && (jpeg[jpeg.length - 2] & 0xff) == 0xff
                && (jpeg[jpeg.length - 1] & 0xff) == 0xd9;
    }

    static int expectedWidth(int rotation) {
        requireRotation(rotation);
        return rotation == 90 || rotation == 270 ? HEIGHT : WIDTH;
    }

    static int expectedHeight(int rotation) {
        requireRotation(rotation);
        return rotation == 90 || rotation == 270 ? WIDTH : HEIGHT;
    }

    private static void requireRotation(int rotation) {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("invalid JPEG rotation");
        }
    }
}
