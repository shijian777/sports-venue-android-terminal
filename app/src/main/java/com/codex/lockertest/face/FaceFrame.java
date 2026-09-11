package com.codex.lockertest.face;

import java.util.Arrays;

public final class FaceFrame implements AutoCloseable {
    public interface Releaser {
        void release(byte[] zeroedNv21);
    }

    public static final class Borrow implements AutoCloseable {
        private FaceFrame owner;
        private byte[] nv21;
        private final long frameId;
        private final int width;
        private final int height;
        private final int sdkRotationDegrees;
        private final int sdkMirror;
        private final int jpegRotationDegrees;
        private final boolean jpegMirror;

        private Borrow(FaceFrame owner, byte[] nv21) {
            this.owner = owner;
            this.nv21 = nv21;
            this.frameId = owner.frameId;
            this.width = owner.width;
            this.height = owner.height;
            this.sdkRotationDegrees = owner.sdkRotationDegrees;
            this.sdkMirror = owner.sdkMirror;
            this.jpegRotationDegrees = owner.jpegRotationDegrees;
            this.jpegMirror = owner.jpegMirror;
        }

        byte[] nv21() {
            synchronized (this) {
                if (owner == null || nv21 == null) {
                    throw new IllegalStateException("borrow is closed");
                }
                return nv21;
            }
        }
        public long frameId() { return frameId; }
        public int width() { return width; }
        public int height() { return height; }
        public int sdkRotationDegrees() { return sdkRotationDegrees; }
        public int sdkMirror() { return sdkMirror; }
        public int jpegRotationDegrees() { return jpegRotationDegrees; }
        public boolean jpegMirror() { return jpegMirror; }

        @Override
        public void close() {
            FaceFrame claimed;
            synchronized (this) {
                claimed = owner;
                owner = null;
                nv21 = null;
            }
            if (claimed != null) claimed.releaseBorrow();
        }
    }

    private final long frameId;
    private final int width;
    private final int height;
    private final int sdkRotationDegrees;
    private final int sdkMirror;
    private final int jpegRotationDegrees;
    private final boolean jpegMirror;
    private final Releaser releaser;
    private byte[] ownedNv21;
    private boolean borrowed;
    private boolean closed;

    public FaceFrame(long frameId, byte[] ownedNv21, int width, int height,
            int sdkRotationDegrees, int sdkMirror,
            int jpegRotationDegrees, boolean jpegMirror, Releaser releaser) {
        if (frameId <= 0L) throw new IllegalArgumentException("frameId must be positive");
        if (ownedNv21 == null) throw new IllegalArgumentException("ownedNv21 cannot be null");
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("NV21 dimensions must be positive and even");
        }
        long pixels = (long) width * (long) height;
        long expected = pixels + pixels / 2L;
        if (expected > Integer.MAX_VALUE || ownedNv21.length != (int) expected) {
            throw new IllegalArgumentException("NV21 byte length does not match dimensions");
        }
        requireRotation(sdkRotationDegrees, "sdkRotationDegrees");
        requireRotation(jpegRotationDegrees, "jpegRotationDegrees");
        if (sdkMirror != 0 && sdkMirror != 1) {
            throw new IllegalArgumentException("sdkMirror must be 0 or 1");
        }
        if (releaser == null) throw new IllegalArgumentException("releaser cannot be null");
        this.frameId = frameId;
        this.ownedNv21 = ownedNv21;
        this.width = width;
        this.height = height;
        this.sdkRotationDegrees = sdkRotationDegrees;
        this.sdkMirror = sdkMirror;
        this.jpegRotationDegrees = jpegRotationDegrees;
        this.jpegMirror = jpegMirror;
        this.releaser = releaser;
    }

    public long frameId() { return frameId; }

    Borrow borrow() {
        synchronized (this) {
            if (closed) throw new IllegalStateException("frame is closed");
            if (borrowed) throw new IllegalStateException("frame is already borrowed");
            borrowed = true;
            return new Borrow(this, ownedNv21);
        }
    }

    @Override
    public void close() {
        byte[] release = null;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (!borrowed) {
                release = ownedNv21;
                ownedNv21 = null;
            }
        }
        release(release);
    }

    private void releaseBorrow() {
        byte[] release = null;
        synchronized (this) {
            if (!borrowed) return;
            borrowed = false;
            if (closed) {
                release = ownedNv21;
                ownedNv21 = null;
            }
        }
        release(release);
    }

    private void release(byte[] bytes) {
        if (bytes == null) return;
        Arrays.fill(bytes, (byte) 0);
        try {
            releaser.release(bytes);
        } catch (RuntimeException ignored) {
            // Buffer ownership is already released; diagnostics belong to the caller layer.
        }
    }

    private static void requireRotation(int degrees, String name) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) {
            throw new IllegalArgumentException(name + " must be 0, 90, 180 or 270");
        }
    }
}
