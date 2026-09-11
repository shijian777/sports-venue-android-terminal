package com.codex.lockertest.integration;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LeaseBoundGenerationGateTest {
    @Test
    public void queuedOldCallbackIsRejectedAfterNewLeaseIsAcquired() {
        ProcessSerialGatewayOwner<FakeGateway> owner = newOwner();
        GenerationGate generations = new GenerationGate();
        LeaseBoundGenerationGate<FakeGateway> callbackGate =
                new LeaseBoundGenerationGate<>(generations, owner);
        long oldGeneration = generations.activate();
        ProcessSerialGatewayOwner.Lease<FakeGateway> oldLease = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                FakeGateway::new);
        AtomicInteger uiUpdates = new AtomicInteger();
        Runnable queuedOldCallback = () -> callbackGate.runIfCurrent(
                oldGeneration,
                oldLease,
                gateway -> uiUpdates.incrementAndGet());

        ProcessSerialGatewayOwner.Lease<FakeGateway> newLease = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                FakeGateway::new);
        queuedOldCallback.run();

        assertEquals(0, uiUpdates.get());
        assertTrue(callbackGate.runIfCurrent(
                oldGeneration,
                newLease,
                gateway -> uiUpdates.incrementAndGet()));
        assertEquals(1, uiUpdates.get());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ProcessSerialGatewayOwner<T> newOwner() {
        try {
            Constructor<ProcessSerialGatewayOwner> constructor =
                    ProcessSerialGatewayOwner.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ProcessSerialGatewayOwner<T>) constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class FakeGateway {
    }
}
