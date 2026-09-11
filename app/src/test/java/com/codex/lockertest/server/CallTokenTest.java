package com.codex.lockertest.server;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CallTokenTest {
    @Test
    public void firstCancellationReasonWinsAndCannotBeOverwritten() {
        CallToken timeoutFirst = new CallToken();
        assertEquals(CallToken.Reason.NONE, timeoutFirst.reason());
        assertFalse(timeoutFirst.isCancelled());

        assertTrue(timeoutFirst.cancel(CallToken.Reason.TIMEOUT));
        assertFalse(timeoutFirst.cancel(CallToken.Reason.CANCELLED));
        assertEquals(CallToken.Reason.TIMEOUT, timeoutFirst.reason());
        assertTrue(timeoutFirst.isCancelled());

        CallToken lifecycleFirst = new CallToken();
        assertTrue(lifecycleFirst.cancel(CallToken.Reason.CANCELLED));
        assertFalse(lifecycleFirst.cancel(CallToken.Reason.TIMEOUT));
        assertEquals(CallToken.Reason.CANCELLED, lifecycleFirst.reason());
    }

    @Test
    public void nullAndNoneAreNotCancellationReasons() {
        assertRejectedCancellation(null);
        assertRejectedCancellation(CallToken.Reason.NONE);
    }

    @Test
    public void registeredCallbackRunsExactlyOnceForTheWinningCancellation() {
        CallToken token = new CallToken();
        AtomicInteger calls = new AtomicInteger();
        CallToken.Registration registration = token.onCancel(calls::incrementAndGet);

        assertTrue(token.cancel(CallToken.Reason.CANCELLED));
        assertFalse(token.cancel(CallToken.Reason.CANCELLED));
        assertFalse(token.cancel(CallToken.Reason.TIMEOUT));
        registration.unregister();
        registration.unregister();

        assertEquals(1, calls.get());
    }

    @Test
    public void unregisterIsIdempotentAndPreventsAQueuedCallback() {
        CallToken token = new CallToken();
        AtomicInteger calls = new AtomicInteger();
        CallToken.Registration registration = token.onCancel(calls::incrementAndGet);

        registration.unregister();
        registration.unregister();
        assertTrue(token.cancel(CallToken.Reason.CANCELLED));

        assertEquals(0, calls.get());
    }

    @Test
    public void registrationAfterCancellationRunsImmediatelyExactlyOnce() {
        CallToken token = new CallToken();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(token.cancel(CallToken.Reason.TIMEOUT));

        CallToken.Registration registration = token.onCancel(calls::incrementAndGet);
        registration.unregister();
        registration.unregister();

        assertEquals(1, calls.get());
        assertEquals(CallToken.Reason.TIMEOUT, token.reason());
    }

    @Test
    public void registrationRacingCancellationNeverLosesOrDuplicatesCallback()
            throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            CallToken token = new CallToken();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> registration = executor.submit(() -> {
                    await(start);
                    token.onCancel(calls::incrementAndGet);
                });
                Future<?> cancellation = executor.submit(() -> {
                    await(start);
                    token.cancel(CallToken.Reason.CANCELLED);
                });

                start.countDown();
                registration.get(2, TimeUnit.SECONDS);
                cancellation.get(2, TimeUnit.SECONDS);

                assertEquals("iteration " + iteration, 1, calls.get());
                assertEquals(CallToken.Reason.CANCELLED, token.reason());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void cancellationSurfaceIsPublicForTheBootstrapCoordinator()
            throws Exception {
        Constructor<CallToken> constructor = CallToken.class.getConstructor();
        Method cancel = CallToken.class.getMethod("cancel", CallToken.Reason.class);
        Method reason = CallToken.class.getMethod("reason");
        Method cancelled = CallToken.class.getMethod("isCancelled");
        Method onCancel = CallToken.class.getMethod("onCancel", Runnable.class);
        Method unregister = CallToken.Registration.class.getMethod("unregister");

        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        assertTrue(Modifier.isPublic(cancel.getModifiers()));
        assertTrue(Modifier.isPublic(reason.getModifiers()));
        assertTrue(Modifier.isPublic(cancelled.getModifiers()));
        assertTrue(Modifier.isPublic(onCancel.getModifiers()));
        assertTrue(Modifier.isPublic(unregister.getModifiers()));
        assertEquals(CallToken.Registration.class, onCancel.getReturnType());
        assertEquals(Arrays.asList(CallToken.Reason.NONE,
                        CallToken.Reason.CANCELLED, CallToken.Reason.TIMEOUT),
                Arrays.asList(CallToken.Reason.values()));
    }

    private static void assertRejectedCancellation(CallToken.Reason reason) {
        CallToken token = new CallToken();
        try {
            token.cancel(reason);
            fail("Expected invalid cancellation reason to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(CallToken.Reason.NONE, token.reason());
            assertFalse(token.isCancelled());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for cancellation race");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Cancellation race interrupted", error);
        }
    }
}
