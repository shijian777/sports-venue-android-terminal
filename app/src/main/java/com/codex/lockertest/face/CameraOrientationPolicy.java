package com.codex.lockertest.face;

import java.util.concurrent.locks.ReentrantLock;

public final class CameraOrientationPolicy {
    private static final int TARGET_FPS = 15000;

    private CameraOrientationPolicy() { }

    public static Transform frontCamera(int sensorOrientation,
            int displayRotationDegrees) {
        requireQuarterTurn(sensorOrientation, "sensorOrientation");
        requireQuarterTurn(displayRotationDegrees, "displayRotationDegrees");
        int upright = (sensorOrientation - displayRotationDegrees + 360) % 360;
        int preview = (360 - ((sensorOrientation + displayRotationDegrees) % 360)) % 360;
        return new Transform(preview, upright, 1, upright, false);
    }

    static int[] selectFpsRange(int[][] copiedSupportedRanges) {
        if (copiedSupportedRanges == null || copiedSupportedRanges.length == 0) {
            throw new IllegalArgumentException("supported FPS ranges cannot be empty");
        }
        int bestMin = 0;
        int bestMax = 0;
        long bestDistance = Long.MAX_VALUE;
        long bestSpan = Long.MAX_VALUE;
        boolean found = false;
        for (int[] candidate : copiedSupportedRanges) {
            if (candidate == null || candidate.length != 2) continue;
            int minimum = candidate[0];
            int maximum = candidate[1];
            if (minimum <= 0 || maximum <= 0 || minimum > maximum) continue;
            long distance;
            if (TARGET_FPS < minimum) distance = (long) minimum - TARGET_FPS;
            else if (TARGET_FPS > maximum) distance = (long) TARGET_FPS - maximum;
            else distance = 0L;
            long span = (long) maximum - minimum;
            if (!found || distance < bestDistance
                    || (distance == bestDistance && span < bestSpan)
                    || (distance == bestDistance && span == bestSpan && maximum < bestMax)
                    || (distance == bestDistance && span == bestSpan
                    && maximum == bestMax && minimum < bestMin)) {
                found = true;
                bestMin = minimum;
                bestMax = maximum;
                bestDistance = distance;
                bestSpan = span;
            }
        }
        if (!found) throw new IllegalArgumentException("no legal supported FPS range");
        return new int[] {bestMin, bestMax};
    }

    private static void requireQuarterTurn(int value, String name) {
        if (value != 0 && value != 90 && value != 180 && value != 270) {
            throw new IllegalArgumentException(name + " must be 0, 90, 180 or 270");
        }
    }

    public static final class Transform {
        private final int previewDisplayOrientation;
        private final int sdkRotationDegrees;
        private final int sdkMirror;
        private final int jpegRotationDegrees;
        private final boolean jpegMirror;

        private Transform(int previewDisplayOrientation, int sdkRotationDegrees,
                int sdkMirror, int jpegRotationDegrees, boolean jpegMirror) {
            this.previewDisplayOrientation = previewDisplayOrientation;
            this.sdkRotationDegrees = sdkRotationDegrees;
            this.sdkMirror = sdkMirror;
            this.jpegRotationDegrees = jpegRotationDegrees;
            this.jpegMirror = jpegMirror;
        }

        public int previewDisplayOrientation() { return previewDisplayOrientation; }
        public int sdkRotationDegrees() { return sdkRotationDegrees; }
        public int sdkMirror() { return sdkMirror; }
        public int jpegRotationDegrees() { return jpegRotationDegrees; }
        public boolean jpegMirror() { return jpegMirror; }
    }
}

final class CameraAttemptStateMachine {
    enum State { IDLE, STARTING, AWAITING_FIRST_FRAME, RUNNING, STOPPING, CLOSED }
    enum FrameClaim { STALE, FIRST, NEXT }

    private long nextGeneration;
    private long activeGeneration;
    private long surfaceRemovalGeneration;
    private boolean surfaceRemovalClaimed;
    private State state = State.IDLE;

    CameraAttemptStateMachine() { this(0L); }

    CameraAttemptStateMachine(long nextGeneration) {
        if (nextGeneration < 0L) {
            throw new IllegalArgumentException("generation cannot be negative");
        }
        this.nextGeneration = nextGeneration;
    }

    long beginStart() {
        if (state != State.IDLE) return 0L;
        if (nextGeneration == Long.MAX_VALUE) {
            state = State.CLOSED;
            activeGeneration = 0L;
            return 0L;
        }
        long generation = ++nextGeneration;
        if (generation <= 0L) {
            state = State.CLOSED;
            activeGeneration = 0L;
            return 0L;
        }
        activeGeneration = generation;
        surfaceRemovalGeneration = generation;
        surfaceRemovalClaimed = false;
        state = State.STARTING;
        return generation;
    }

    boolean markPreviewActive(long generation) {
        if (generation != activeGeneration || state != State.STARTING) return false;
        state = State.AWAITING_FIRST_FRAME;
        return true;
    }

    FrameClaim claimFrame(long generation) {
        if (generation != activeGeneration) return FrameClaim.STALE;
        if (state == State.AWAITING_FIRST_FRAME) {
            state = State.RUNNING;
            return FrameClaim.FIRST;
        }
        return state == State.RUNNING ? FrameClaim.NEXT : FrameClaim.STALE;
    }

    boolean cancelStartup(long generation) {
        if (generation != activeGeneration) return false;
        if (state != State.STARTING && state != State.AWAITING_FIRST_FRAME) return false;
        state = State.STOPPING;
        return true;
    }

    boolean beginStop() {
        if (state == State.IDLE || state == State.STOPPING || state == State.CLOSED) {
            return false;
        }
        state = State.STOPPING;
        return true;
    }

    void finishStop(boolean reusable) {
        if (state == State.CLOSED) return;
        if (state != State.STOPPING) return;
        activeGeneration = 0L;
        state = reusable ? State.IDLE : State.CLOSED;
    }

    void close() {
        state = State.CLOSED;
        activeGeneration = 0L;
    }

    boolean claimSurfaceRemoval(long generation) {
        if (generation <= 0L || generation != surfaceRemovalGeneration
                || surfaceRemovalClaimed) return false;
        surfaceRemovalClaimed = true;
        return true;
    }

    boolean mayReturnBuffer(long generation) {
        return generation == activeGeneration
                && (state == State.AWAITING_FIRST_FRAME || state == State.RUNNING);
    }

    boolean isRunning() {
        return state == State.AWAITING_FIRST_FRAME || state == State.RUNNING;
    }

    State state() { return state; }
    long activeGeneration() { return activeGeneration; }
}

/** Pure serialization seam used to prove the emergency cleanup ordering contract. */
final class CameraHalGate {
    private final ReentrantLock gate = new ReentrantLock();
    private long cleanedGeneration;
    private int waiterCount;

    boolean ownerCall(long generation, Runnable operation) {
        if (operation == null || generation <= 0L) {
            throw new IllegalArgumentException("owner operation must be valid");
        }
        lockUninterruptibly();
        try {
            if (generation <= cleanedGeneration) return false;
            operation.run();
            return true;
        } finally {
            gate.unlock();
        }
    }

    boolean cleanupOnce(long generation, Runnable cleanup) {
        if (cleanup == null || generation <= 0L) {
            throw new IllegalArgumentException("cleanup must be valid");
        }
        lockUninterruptibly();
        try {
            if (generation <= cleanedGeneration) return false;
            cleanedGeneration = generation;
            cleanup.run();
            return true;
        } finally {
            gate.unlock();
        }
    }

    private void lockUninterruptibly() {
        boolean interrupted = false;
        boolean waiting = gate.isLocked() && !gate.isHeldByCurrentThread();
        if (waiting) {
            synchronized (this) { waiterCount++; }
        }
        for (;;) {
            try {
                gate.lockInterruptibly();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (waiting) {
            synchronized (this) { waiterCount--; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized int waiterCountForTest() { return waiterCount; }
}
