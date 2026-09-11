package com.codex.lockertest.integration;

public final class OwnershipHandoffGate {
    private long generation;
    private long pendingToken;
    private Runnable pendingAction;

    public synchronized long begin(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        generation = next(generation);
        pendingToken = generation;
        pendingAction = action;
        return pendingToken;
    }

    public boolean complete(long token) {
        Runnable action;
        synchronized (this) {
            if (token <= 0L || token != pendingToken || pendingAction == null) {
                return false;
            }
            action = pendingAction;
            pendingToken = 0L;
            pendingAction = null;
        }
        action.run();
        return true;
    }

    public synchronized void invalidate() {
        generation = next(generation);
        pendingToken = 0L;
        pendingAction = null;
    }

    private static long next(long value) {
        value++;
        return value > 0L ? value : 1L;
    }
}
