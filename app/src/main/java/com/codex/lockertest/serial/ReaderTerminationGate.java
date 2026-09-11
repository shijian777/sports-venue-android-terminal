package com.codex.lockertest.serial;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ReaderTerminationGate {
    private final CountDownLatch stopped;

    private ReaderTerminationGate(int count) {
        stopped = new CountDownLatch(count);
    }

    static ReaderTerminationGate running() {
        return new ReaderTerminationGate(1);
    }

    static ReaderTerminationGate stopped() {
        return new ReaderTerminationGate(0);
    }

    void signalStopped() {
        stopped.countDown();
    }

    boolean awaitStopped(long timeoutMillis) {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis cannot be negative");
        }
        try {
            return stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
