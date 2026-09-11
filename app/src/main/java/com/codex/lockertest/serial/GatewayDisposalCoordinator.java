package com.codex.lockertest.serial;

import java.util.ArrayList;
import java.util.List;

final class GatewayDisposalCoordinator {
    interface Queue {
        void execute(Runnable task);

        void shutdownAfterQueuedTasks();
    }

    interface SnapshotFactory {
        ResourceSnapshot detach();
    }

    interface ResourceSnapshot {
        void closeAndInterrupt();

        boolean awaitTermination();
    }

    interface Completion {
        void onComplete(boolean success);
    }

    private final Queue queue;
    private final List<Completion> completions = new ArrayList<>();
    private boolean started;
    private boolean complete;
    private boolean outcome;

    GatewayDisposalCoordinator(Queue queue) {
        if (queue == null) {
            throw new IllegalArgumentException("queue cannot be null");
        }
        this.queue = queue;
    }

    void dispose(SnapshotFactory snapshotFactory, Completion completion) {
        if (snapshotFactory == null) {
            throw new IllegalArgumentException("snapshotFactory cannot be null");
        }

        ResourceSnapshot snapshot;
        synchronized (this) {
            if (complete) {
                notifyOne(completion, outcome);
                return;
            }
            if (completion != null) {
                completions.add(completion);
            }
            if (started) {
                return;
            }
            started = true;
            try {
                snapshot = snapshotFactory.detach();
            } catch (Throwable failure) {
                finish(false);
                return;
            }
        }

        try {
            snapshot.closeAndInterrupt();
        } catch (Throwable failure) {
            finish(false);
            return;
        }

        try {
            queue.execute(() -> {
                boolean success;
                try {
                    success = snapshot.awaitTermination();
                } catch (Throwable failure) {
                    success = false;
                }
                finish(success);
            });
        } catch (Throwable rejection) {
            finish(false);
            return;
        }
        try {
            queue.shutdownAfterQueuedTasks();
        } catch (Throwable ignored) {
            // The authoritative FIFO barrier is already accepted.
        }
    }

    private void finish(boolean success) {
        List<Completion> callbacks;
        synchronized (this) {
            if (complete) {
                return;
            }
            complete = true;
            outcome = success;
            callbacks = new ArrayList<>(completions);
            completions.clear();
        }
        for (Completion callback : callbacks) {
            notifyOne(callback, success);
        }
    }

    private static void notifyOne(Completion completion, boolean success) {
        if (completion == null) {
            return;
        }
        try {
            completion.onComplete(success);
        } catch (Throwable ignored) {
            // One consumer cannot prevent other disposal waiters from completing.
        }
    }
}
