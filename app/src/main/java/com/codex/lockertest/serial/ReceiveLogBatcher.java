package com.codex.lockertest.serial;

/**
 * Batches receive text for one serial-listener generation at a time.
 * All generation and scheduling state changes share one monitor so stale
 * readers and stale UI flushes cannot affect a replacement generation.
 */
public final class ReceiveLogBatcher {
    private final int maxPendingCharacters;
    private final StringBuilder pending = new StringBuilder();

    private long activeGeneration;
    private boolean flushScheduled;

    public ReceiveLogBatcher(int maxPendingCharacters) {
        if (maxPendingCharacters <= 0) {
            throw new IllegalArgumentException("maxPendingCharacters must be positive");
        }
        this.maxPendingCharacters = maxPendingCharacters;
    }

    public synchronized void activate(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        activeGeneration = generation;
        pending.setLength(0);
        flushScheduled = false;
    }

    public synchronized void invalidate(long generation) {
        if (generation <= 0L || generation != activeGeneration) {
            return;
        }
        activeGeneration = 0L;
        pending.setLength(0);
        flushScheduled = false;
    }

    /** Returns true only when the caller must schedule a UI flush. */
    public synchronized boolean enqueue(long generation, String text) {
        if (generation <= 0L || generation != activeGeneration
                || text == null || text.isEmpty()) {
            return false;
        }
        appendBounded(text);
        if (flushScheduled) {
            return false;
        }
        flushScheduled = true;
        return true;
    }

    /** Returns an empty value for stale generations without mutating current state. */
    public synchronized String flush(long generation) {
        if (generation <= 0L || generation != activeGeneration) {
            return "";
        }
        String value = pending.toString();
        pending.setLength(0);
        flushScheduled = false;
        return value;
    }

    public synchronized int pendingLength() {
        return pending.length();
    }

    private void appendBounded(String text) {
        if (text.length() >= maxPendingCharacters) {
            pending.setLength(0);
            pending.append(text, text.length() - maxPendingCharacters, text.length());
            return;
        }

        int separatorLength = pending.length() == 0 ? 0 : 1;
        int overflow = pending.length() + separatorLength + text.length()
                - maxPendingCharacters;
        if (overflow > 0) {
            pending.delete(0, Math.min(overflow, pending.length()));
            while (pending.length() > 0 && pending.charAt(0) == ' ') {
                pending.deleteCharAt(0);
            }
        }
        if (pending.length() > 0) {
            pending.append(' ');
        }
        pending.append(text);
    }
}
