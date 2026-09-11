package com.codex.lockertest.integration;

/**
 * Identifies callbacks belonging to the currently owned asynchronous resource.
 */
public final class GenerationGate {
    private long generation;
    private boolean active;

    public synchronized long activate() {
        generation = next(generation);
        active = true;
        return generation;
    }

    public synchronized void invalidate() {
        generation = next(generation);
        active = false;
    }

    public synchronized boolean accepts(long candidate) {
        return active && candidate > 0L && candidate == generation;
    }

    private static long next(long value) {
        value++;
        return value > 0L ? value : 1L;
    }
}
