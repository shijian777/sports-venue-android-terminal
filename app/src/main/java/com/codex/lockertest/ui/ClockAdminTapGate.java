package com.codex.lockertest.ui;

/** Bounded five-tap gate for the hidden administrator entry on the home clock. */
public final class ClockAdminTapGate {
    private static final int REQUIRED_TAPS = 5;
    private static final long MAX_GAP_MILLIS = 1_500L;

    private int tapCount;
    private long lastTapMillis = -1L;

    public boolean tap(long nowElapsedMillis) {
        if (nowElapsedMillis < 0L) {
            reset();
            return false;
        }
        if (tapCount > 0) {
            if (nowElapsedMillis < lastTapMillis) {
                reset();
                return false;
            }
            if (nowElapsedMillis - lastTapMillis > MAX_GAP_MILLIS) {
                reset();
            }
        }
        tapCount++;
        lastTapMillis = nowElapsedMillis;
        if (tapCount == REQUIRED_TAPS) {
            reset();
            return true;
        }
        return false;
    }

    public void reset() {
        tapCount = 0;
        lastTapMillis = -1L;
    }
}
