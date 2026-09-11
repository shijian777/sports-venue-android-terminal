package com.codex.lockertest.ui.business;

import android.os.Handler;
import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded network lane per host; main thread owns timers and rendering only. */
final class AndroidBusinessScheduler implements OnlineCustomerCoordinator.Scheduler, AutoCloseable {
    private final Handler handler;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "online-customer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    AndroidBusinessScheduler(Handler handler) { this.handler = handler; }

    @Override public OnlineCustomerCoordinator.Cancellable submit(Runnable action) {
        Future<?> pending = worker.submit(action);
        return () -> { pending.cancel(true); worker.purge(); };
    }

    @Override public OnlineCustomerCoordinator.Cancellable schedule(Runnable action, long delayMillis) {
        if (!handler.postDelayed(action, delayMillis))
            throw new java.util.concurrent.RejectedExecutionException("UI scheduler unavailable");
        return () -> handler.removeCallbacks(action);
    }

    @Override public void close() { worker.shutdownNow(); }
}
