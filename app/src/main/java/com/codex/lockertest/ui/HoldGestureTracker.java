package com.codex.lockertest.ui;

public final class HoldGestureTracker {
    private final long holdDurationMillis;

    private boolean active;
    private boolean fired;
    private long downTimeMillis;

    public HoldGestureTracker(long holdDurationMillis, float movementTolerance) {
        if (holdDurationMillis <= 0L) {
            throw new IllegalArgumentException("holdDurationMillis must be greater than zero");
        }
        if (movementTolerance < 0f) {
            throw new IllegalArgumentException("movementTolerance cannot be negative");
        }
        this.holdDurationMillis = holdDurationMillis;
    }

    public void onDown(long timeMillis, float x, float y) {
        active = true;
        fired = false;
        downTimeMillis = timeMillis;
    }

    public void onMove(float x, float y) {
        active = false;
    }

    public void onUpOrCancel() {
        active = false;
    }

    public boolean onTime(long timeMillis) {
        if (!active || fired || timeMillis - downTimeMillis < holdDurationMillis) {
            return false;
        }
        fired = true;
        active = false;
        return true;
    }
}
