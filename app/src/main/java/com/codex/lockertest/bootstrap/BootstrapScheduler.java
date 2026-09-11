package com.codex.lockertest.bootstrap;

import java.util.concurrent.Future;

/** Owns worker, retry, and watchdog jobs without exposing an executor globally. */
public interface BootstrapScheduler {
    Future<?> submit(Runnable task);

    Future<?> schedule(Runnable task, long delayMillis);
}
