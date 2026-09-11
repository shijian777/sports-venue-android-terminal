package com.codex.lockertest.face;

public final class LatestFaceFrameMailbox implements AutoCloseable {
    private FaceFrame pending;
    private long highestOfferedFrameId;
    private boolean closed;

    public boolean offer(FaceFrame ownedFrame) {
        if (ownedFrame == null) throw new IllegalArgumentException("ownedFrame cannot be null");
        long offeredFrameId = ownedFrame.frameId();
        FaceFrame release;
        boolean accepted;
        synchronized (this) {
            if (closed || offeredFrameId <= highestOfferedFrameId) {
                release = ownedFrame;
                accepted = false;
            } else {
                highestOfferedFrameId = offeredFrameId;
                release = pending;
                pending = ownedFrame;
                accepted = true;
            }
        }
        safeClose(release);
        return accepted;
    }

    public FaceFrame take() {
        synchronized (this) {
            FaceFrame result = pending;
            pending = null;
            return result;
        }
    }

    @Override
    public void close() {
        FaceFrame release;
        synchronized (this) {
            if (closed) return;
            closed = true;
            release = pending;
            pending = null;
        }
        safeClose(release);
    }

    private static void safeClose(FaceFrame frame) {
        if (frame == null) return;
        try {
            frame.close();
        } catch (RuntimeException ignored) {
            // FaceFrame close is idempotent; continue mailbox cleanup.
        }
    }
}
