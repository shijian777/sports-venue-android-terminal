package com.codex.lockertest.serial;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IdentityListenerRegistryTest {
    @Test
    public void newestSubscriberReceivesEventsAndStaleRemovalCannotDetachIt() {
        IdentityListenerRegistry<String> registry = new IdentityListenerRegistry<>();
        IdentityListenerRegistry.Subscription<String> oldAdmin = registry.replace("admin-old");
        IdentityListenerRegistry.Subscription<String> customer = registry.replace("customer");
        List<String> delivered = new ArrayList<>();

        assertFalse(registry.remove(oldAdmin));
        assertTrue(registry.deliver(delivered::add));
        assertEquals(Arrays.asList("customer"), delivered);
        assertTrue(registry.isCurrent(customer));
    }

    @Test
    public void sameRoleRecreationAndQueuedOldDeliveryAreIdentityGated() {
        IdentityListenerRegistry<String> registry = new IdentityListenerRegistry<>();
        IdentityListenerRegistry.Subscription<String> oldAdmin = registry.replace("admin-old");
        IdentityListenerRegistry.Subscription<String> newAdmin = registry.replace("admin-new");
        List<String> delivered = new ArrayList<>();

        assertFalse(registry.remove(oldAdmin));
        assertTrue(registry.deliver(delivered::add));
        assertEquals(Arrays.asList("admin-new"), delivered);
        assertTrue(registry.remove(newAdmin));
        assertFalse(registry.deliver(delivered::add));
    }

    @Test
    public void replacementCannotBecomeCurrentUntilInFlightOldDeliveryFinishes()
            throws Exception {
        IdentityListenerRegistry<Runnable> registry = new IdentityListenerRegistry<>();
        CountDownLatch oldDeliveryStarted = new CountDownLatch(1);
        CountDownLatch finishOldDelivery = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        registry.replace(() -> {
            oldDeliveryStarted.countDown();
            try {
                if (!finishOldDelivery.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("old delivery was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });

        Thread delivery = new Thread(() -> {
            try {
                registry.deliver(Runnable::run);
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        });
        Thread replacement = new Thread(() -> {
            replacementStarted.countDown();
            try {
                registry.replace(() -> { });
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            } finally {
                replacementFinished.countDown();
            }
        });
        delivery.start();
        assertTrue(oldDeliveryStarted.await(2, TimeUnit.SECONDS));
        replacement.start();
        assertTrue(replacementStarted.await(2, TimeUnit.SECONDS));

        assertFalse(replacementFinished.await(100, TimeUnit.MILLISECONDS));
        finishOldDelivery.countDown();
        delivery.join(2_000L);
        replacement.join(2_000L);

        assertFalse(delivery.isAlive());
        assertFalse(replacement.isAlive());
        if (threadFailure.get() != null) {
            throw new AssertionError(threadFailure.get());
        }
    }
}
