package com.codex.lockertest.face;

import com.codex.lockertest.face.verification.FaceJpegContract;

import java.util.Arrays;

public final class FaceCapture implements AutoCloseable {
    public static final class JpegBorrow implements AutoCloseable {
        private FaceCapture owner;
        private byte[] jpeg;

        private JpegBorrow(FaceCapture owner, byte[] jpeg) {
            this.owner = owner;
            this.jpeg = jpeg;
        }

        byte[] jpeg() {
            synchronized (this) {
                if (owner == null || jpeg == null) {
                    throw new IllegalStateException("JPEG borrow is closed");
                }
                return jpeg;
            }
        }

        @Override
        public void close() {
            FaceCapture claimed;
            synchronized (this) {
                claimed = owner;
                owner = null;
                jpeg = null;
            }
            if (claimed != null) claimed.releaseJpegBorrow();
        }
    }

    private final long sessionId;
    private final long selectedFrameId;
    private FaceFrame ownedFrame;
    private byte[] ownedJpeg;
    private boolean jpegBorrowed;
    private boolean closed;

    FaceCapture(long sessionId, FaceFrame ownedFrame) {
        if (sessionId <= 0L) throw new IllegalArgumentException("sessionId must be positive");
        if (ownedFrame == null) throw new IllegalArgumentException("ownedFrame cannot be null");
        this.sessionId = sessionId;
        this.selectedFrameId = ownedFrame.frameId();
        this.ownedFrame = ownedFrame;
    }

    long frameId() { return selectedFrameId; }

    String requestId() { return "face-" + sessionId + "-" + frameId(); }

    FaceFrame.Borrow borrowFrame() {
        FaceFrame frame;
        synchronized (this) {
            if (closed) throw new IllegalStateException("capture is closed");
            frame = ownedFrame;
        }
        return frame.borrow();
    }

    boolean acceptOwnedJpeg(byte[] ownedJpeg) {
        if (!validEnvelope(ownedJpeg)) {
            zero(ownedJpeg);
            return false;
        }
        boolean accepted;
        synchronized (this) {
            accepted = !closed && this.ownedJpeg == null;
            if (accepted) this.ownedJpeg = ownedJpeg;
        }
        if (!accepted) zero(ownedJpeg);
        return accepted;
    }

    JpegBorrow borrowJpeg() {
        synchronized (this) {
            if (closed) throw new IllegalStateException("capture is closed");
            if (ownedJpeg == null) throw new IllegalStateException("capture has no JPEG");
            if (jpegBorrowed) throw new IllegalStateException("JPEG is already borrowed");
            jpegBorrowed = true;
            return new JpegBorrow(this, ownedJpeg);
        }
    }

    @Override
    public void close() {
        FaceFrame frame;
        byte[] jpeg = null;
        synchronized (this) {
            if (closed) return;
            closed = true;
            frame = ownedFrame;
            ownedFrame = null;
            if (!jpegBorrowed) {
                jpeg = ownedJpeg;
                ownedJpeg = null;
            }
        }
        safeClose(frame);
        zero(jpeg);
    }

    private void releaseJpegBorrow() {
        byte[] jpeg = null;
        synchronized (this) {
            if (!jpegBorrowed) return;
            jpegBorrowed = false;
            if (closed) {
                jpeg = ownedJpeg;
                ownedJpeg = null;
            }
        }
        zero(jpeg);
    }

    private static boolean validEnvelope(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > FaceJpegContract.MAX_BYTES) return false;
        int last = jpeg.length - 1;
        return (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8
                && (jpeg[last - 1] & 0xff) == 0xff && (jpeg[last] & 0xff) == 0xd9;
    }

    private static void safeClose(FaceFrame frame) {
        if (frame == null) return;
        try {
            frame.close();
        } catch (RuntimeException ignored) {
            // Continue JPEG cleanup.
        }
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
