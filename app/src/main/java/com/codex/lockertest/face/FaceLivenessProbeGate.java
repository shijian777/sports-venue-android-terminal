package com.codex.lockertest.face;

/**
 * Serializes optional-liveness probe state independently from the base face
 * runtime. A permanent release always wins over late vendor callbacks.
 */
public final class FaceLivenessProbeGate {
    private final FaceLivenessControl control;
    private long generation;
    private long physicalAttemptId;
    private boolean active;
    private boolean released;

    public FaceLivenessProbeGate(FaceLivenessControl control) {
        if (control == null) throw new IllegalArgumentException("control cannot be null");
        this.control = control;
    }

    public synchronized boolean begin(long generation, long physicalAttemptId) {
        if (generation <= 0L || physicalAttemptId <= 0L) {
            throw new IllegalArgumentException("probe identity must be positive");
        }
        if (released) return false;
        this.generation = generation;
        this.physicalAttemptId = physicalAttemptId;
        active = true;
        control.onProbeStarted();
        return true;
    }

    public synchronized boolean complete(long generation,
            long physicalAttemptId, int code) {
        if (!matchesActive(generation, physicalAttemptId)) return false;
        active = false;
        if (code == 0) {
            control.onProbeSupported();
        } else if (code == 10) {
            control.onProbeUnsupported();
        } else {
            control.onProbeFailed();
        }
        return true;
    }

    public synchronized boolean fail(long generation, long physicalAttemptId) {
        if (!matchesActive(generation, physicalAttemptId)) return false;
        active = false;
        control.onProbeFailed();
        return true;
    }

    public synchronized boolean runtimeUnavailable(long generation,
            long physicalAttemptId) {
        if (released || this.generation != generation
                || this.physicalAttemptId != physicalAttemptId) return false;
        FaceLivenessControl.Capability capability = control.snapshot().capability();
        if (capability != FaceLivenessControl.Capability.PROBING
                && capability != FaceLivenessControl.Capability.SUPPORTED) return false;
        active = false;
        control.onProbeFailed();
        return true;
    }

    public synchronized boolean inferenceUnavailable(long generation,
            long physicalAttemptId) {
        if (released || this.generation != generation
                || this.physicalAttemptId != physicalAttemptId
                || control.snapshot().capability()
                != FaceLivenessControl.Capability.SUPPORTED) return false;
        control.onProbeFailed();
        return true;
    }

    public synchronized boolean isActive(long generation, long physicalAttemptId) {
        return matchesActive(generation, physicalAttemptId);
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        active = false;
        control.onLicenseUnavailable();
    }

    private boolean matchesActive(long generation, long physicalAttemptId) {
        return !released && active && this.generation == generation
                && this.physicalAttemptId == physicalAttemptId;
    }
}
