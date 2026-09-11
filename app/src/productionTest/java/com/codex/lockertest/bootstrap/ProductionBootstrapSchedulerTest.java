package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionBootstrapSchedulerTest {
    @Test
    public void submitRunsOnOneOwnedDaemonWorker() throws Exception {
        ProductionBootstrapScheduler scheduler = new ProductionBootstrapScheduler();
        CountDownLatch completed = new CountDownLatch(1);
        boolean[] daemon = new boolean[1];
        try {
            Future<?> future = scheduler.submit(() -> {
                daemon[0] = Thread.currentThread().isDaemon();
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            future.get(2, TimeUnit.SECONDS);
            assertTrue(daemon[0]);
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void closeCancelsDelayedWorkAndRejectsNewJobs() {
        ProductionBootstrapScheduler scheduler = new ProductionBootstrapScheduler();
        CountDownLatch invoked = new CountDownLatch(1);
        Future<?> delayed = scheduler.schedule(invoked::countDown, 60_000L);

        scheduler.close();
        scheduler.close();

        assertTrue(delayed.isCancelled());
        assertFalse(invoked.getCount() == 0L);
        try {
            scheduler.submit(() -> { });
            fail("Closed scheduler must reject new work");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        try {
            scheduler.schedule(() -> { }, 0L);
            fail("Closed scheduler must reject new timers");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void watchdogRunsWhileNetworkWorkerIsBlocked() throws Exception {
        ProductionBootstrapScheduler scheduler = new ProductionBootstrapScheduler();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch watchdogRan = new CountDownLatch(1);
        try {
            Future<?> worker = scheduler.submit(() -> {
                workerEntered.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS));

            Future<?> watchdog = scheduler.schedule(watchdogRan::countDown, 25L);

            assertTrue("watchdog must not share the blocked network worker",
                    watchdogRan.await(1, TimeUnit.SECONDS));
            watchdog.get(1, TimeUnit.SECONDS);
            releaseWorker.countDown();
            worker.get(1, TimeUnit.SECONDS);
        } finally {
            releaseWorker.countDown();
            scheduler.close();
        }
    }

    @Test
    public void completedWorkIsPrunedWhenMoreWorkIsAccepted() throws Exception {
        ProductionBootstrapScheduler scheduler = new ProductionBootstrapScheduler();
        try {
            for (int index = 0; index < 50; index++) {
                scheduler.submit(() -> { }).get(1, TimeUnit.SECONDS);
            }
            Future<?> pending = scheduler.schedule(() -> { }, 60_000L);
            Field ownedField = ProductionBootstrapScheduler.class
                    .getDeclaredField("owned");
            ownedField.setAccessible(true);
            Set<?> owned = (Set<?>) ownedField.get(scheduler);

            assertTrue("completed futures must not accumulate for the Activity lifetime",
                    owned.size() <= 1);
            assertTrue(owned.contains(pending));
        } finally {
            scheduler.close();
        }
    }
}
