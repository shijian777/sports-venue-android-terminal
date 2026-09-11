package com.codex.lockertest.ui;

/** Short post-scan gate that consumes a keyboard reader's delayed CR/LF or key-up tail. */
public final class IdCardEventDrain {
    private long inclusiveDeadline;
    private int deviceId;
    private boolean active;

    public void begin(long now, int deviceId, long durationMillis) {
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis cannot be negative");
        }
        this.deviceId = deviceId;
        inclusiveDeadline = safeAdd(now, durationMillis);
        active = true;
    }

    public boolean shouldConsume(
            long now,
            int eventDeviceId,
            boolean idCardInputKey) {
        if (!active || !idCardInputKey || eventDeviceId != deviceId) {
            return false;
        }
        if (now <= inclusiveDeadline) {
            return true;
        }
        clear();
        return false;
    }

    public void clear() {
        active = false;
        inclusiveDeadline = 0L;
        deviceId = 0;
    }

    private static long safeAdd(long value, long delta) {
        long result = value + delta;
        return result < value ? Long.MAX_VALUE : result;
    }
}
