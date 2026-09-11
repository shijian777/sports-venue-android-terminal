package com.codex.lockertest.ui;

/** Small lifecycle-independent state machine for the invalid-credential recovery timer. */
public final class CredentialRecoveryCountdown {
    private final int durationSeconds;
    private int secondsRemaining;
    private boolean active;

    public CredentialRecoveryCountdown(int durationSeconds) {
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        this.durationSeconds = durationSeconds;
    }

    public void start() {
        secondsRemaining = durationSeconds;
        active = true;
    }

    /** Advances one elapsed second and returns whether the countdown remains active. */
    public boolean tick() {
        if (!active) {
            return false;
        }
        secondsRemaining--;
        if (secondsRemaining <= 0) {
            cancel();
            return false;
        }
        return true;
    }

    public void cancel() {
        secondsRemaining = 0;
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public int secondsRemaining() {
        return secondsRemaining;
    }
}
