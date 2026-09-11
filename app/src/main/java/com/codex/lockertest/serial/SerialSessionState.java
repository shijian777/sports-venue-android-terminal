package com.codex.lockertest.serial;

public final class SerialSessionState {
    public enum ConnectionAction {
        OPEN,
        REUSE,
        WAIT,
        UNAVAILABLE
    }

    public enum Phase {
        CLOSED,
        OPENING,
        OPEN,
        CLOSING,
        DISPOSED
    }

    private Phase value = Phase.CLOSED;
    private long closeGeneration;
    private long pendingCloseToken;

    public synchronized boolean beginOpen() {
        if (value != Phase.CLOSED) {
            return false;
        }
        value = Phase.OPENING;
        return true;
    }

    public synchronized boolean markOpened() {
        return commitOpen(() -> { });
    }

    public synchronized boolean commitOpen(Runnable resourcePublisher) {
        if (resourcePublisher == null) {
            throw new IllegalArgumentException("resourcePublisher cannot be null");
        }
        if (value != Phase.OPENING) {
            return false;
        }
        resourcePublisher.run();
        value = Phase.OPEN;
        return true;
    }

    public synchronized boolean runIfOpen(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (value != Phase.OPEN) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized void markOpenFailed() {
        if (value == Phase.OPENING) {
            value = Phase.CLOSED;
        }
    }

    public synchronized long beginClose() {
        if (value != Phase.OPEN && value != Phase.OPENING) {
            return 0L;
        }
        value = Phase.CLOSING;
        closeGeneration = next(closeGeneration);
        pendingCloseToken = closeGeneration;
        return pendingCloseToken;
    }

    public synchronized boolean completeClose(
            long closeToken,
            boolean readerTerminated) {
        return completeClose(closeToken, readerTerminated, () -> { });
    }

    public synchronized boolean completeClose(
            long closeToken,
            boolean readerTerminated,
            Runnable beforeReopen) {
        if (beforeReopen == null) {
            throw new IllegalArgumentException("beforeReopen cannot be null");
        }
        if (!readerTerminated
                || value != Phase.CLOSING
                || closeToken <= 0L
                || closeToken != pendingCloseToken) {
            return false;
        }
        try {
            beforeReopen.run();
        } catch (Throwable ignored) {
            // A listener cannot leave an already terminated reader session poisoned.
        }
        if (value != Phase.CLOSING || closeToken != pendingCloseToken) {
            return false;
        }
        pendingCloseToken = 0L;
        value = Phase.CLOSED;
        return true;
    }

    public synchronized boolean isClosing(long closeToken) {
        return value == Phase.CLOSING
                && closeToken > 0L
                && closeToken == pendingCloseToken;
    }

    public synchronized boolean runIfClosing(long closeToken, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (!isClosing(closeToken)) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized boolean canSend() {
        return value == Phase.OPEN;
    }

    public synchronized Phase phase() {
        return value;
    }

    public synchronized ConnectionAction connectionAction() {
        switch (value) {
            case CLOSED:
                return ConnectionAction.OPEN;
            case OPEN:
                return ConnectionAction.REUSE;
            case OPENING:
            case CLOSING:
                return ConnectionAction.WAIT;
            case DISPOSED:
            default:
                return ConnectionAction.UNAVAILABLE;
        }
    }

    public synchronized void dispose() {
        pendingCloseToken = 0L;
        value = Phase.DISPOSED;
    }

    public synchronized boolean isDisposed() {
        return value == Phase.DISPOSED;
    }

    private static long next(long value) {
        value++;
        return value > 0L ? value : 1L;
    }
}
