package com.codex.lockertest.bootstrap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Isolates blocking bootstrap calls from retry and watchdog timing. */
final class ProductionBootstrapScheduler implements BootstrapScheduler, AutoCloseable {
    private final Object lock = new Object();
    private final ExecutorService workerExecutor;
    private final ScheduledThreadPoolExecutor timerExecutor;
    private final Set<Future<?>> owned = new HashSet<>();
    private boolean closed;

    ProductionBootstrapScheduler() {
        workerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "locker-bootstrap-worker");
            thread.setDaemon(true);
            return thread;
        });
        timerExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "locker-bootstrap-timer");
            thread.setDaemon(true);
            return thread;
        });
        timerExecutor.setRemoveOnCancelPolicy(true);
        timerExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timerExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    public Future<?> submit(Runnable task) {
        if (task == null) throw new IllegalArgumentException("Bootstrap task is required");
        synchronized (lock) {
            ensureOpenLocked();
            try {
                Future<?> future = workerExecutor.submit(task);
                pruneCompletedLocked();
                owned.add(future);
                return future;
            } catch (RejectedExecutionException rejected) {
                throw new IllegalStateException("Bootstrap scheduler is closed");
            }
        }
    }

    @Override
    public Future<?> schedule(Runnable task, long delayMillis) {
        if (task == null || delayMillis < 0L) {
            throw new IllegalArgumentException("Invalid bootstrap schedule");
        }
        synchronized (lock) {
            ensureOpenLocked();
            try {
                Future<?> future = timerExecutor.schedule(
                        task, delayMillis, TimeUnit.MILLISECONDS);
                pruneCompletedLocked();
                owned.add(future);
                return future;
            } catch (RejectedExecutionException rejected) {
                throw new IllegalStateException("Bootstrap scheduler is closed");
            }
        }
    }

    @Override
    public void close() {
        List<Future<?>> pending;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(owned);
            owned.clear();
        }
        for (Future<?> future : pending) {
            future.cancel(true);
        }
        workerExecutor.shutdownNow();
        timerExecutor.shutdownNow();
    }

    private void ensureOpenLocked() {
        if (closed) throw new IllegalStateException("Bootstrap scheduler is closed");
    }

    private void pruneCompletedLocked() {
        Iterator<Future<?>> iterator = owned.iterator();
        while (iterator.hasNext()) {
            Future<?> future = iterator.next();
            if (future.isDone() || future.isCancelled()) iterator.remove();
        }
    }
}
