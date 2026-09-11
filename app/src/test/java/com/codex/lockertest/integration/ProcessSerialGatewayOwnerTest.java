package com.codex.lockertest.integration;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ProcessSerialGatewayOwnerTest {
    @Test
    public void productionOwnerCannotBeConstructedOutsideSingleton() throws Exception {
        Constructor<?> constructor =
                ProcessSerialGatewayOwner.class.getDeclaredConstructor();

        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    public void persistentGatewayIsCreatedOnceAndReusedAcrossRoles() {
        ProcessSerialGatewayOwner<FakeGateway> owner =
                newOwner();
        AtomicInteger factories = new AtomicInteger();
        AtomicReference<FakeGateway> created = new AtomicReference<>();

        ProcessSerialGatewayOwner.Lease<FakeGateway> admin = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                () -> {
                    FakeGateway gateway =
                            new FakeGateway("gateway-" + factories.incrementAndGet());
                    created.set(gateway);
                    return gateway;
                });
        assertTrue(owner.withGateway(
                admin, gateway -> gateway == created.get(), false));
        assertTrue(owner.relinquish(admin));

        ProcessSerialGatewayOwner.Lease<FakeGateway> customer = owner.acquire(
                ProcessSerialGatewayOwner.Role.CUSTOMER,
                () -> new FakeGateway("replacement-" + factories.incrementAndGet()));

        assertEquals(1, factories.get());
        assertTrue(owner.withGateway(
                customer, gateway -> gateway == created.get(), false));
        assertEquals(ProcessSerialGatewayOwner.Role.CUSTOMER, owner.currentRole());
    }

    @Test
    public void staleCrossRoleLeaseCannotOperateOrRelinquishCurrentClient() {
        ProcessSerialGatewayOwner<FakeGateway> owner =
                newOwner();
        ProcessSerialGatewayOwner.Lease<FakeGateway> oldAdmin = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                () -> new FakeGateway("shared"));
        ProcessSerialGatewayOwner.Lease<FakeGateway> customer = owner.acquire(
                ProcessSerialGatewayOwner.Role.CUSTOMER,
                () -> new FakeGateway("must-not-be-created"));

        assertNull(owner.withGateway(oldAdmin, gateway -> gateway, null));
        assertFalse(owner.relinquish(oldAdmin));
        assertTrue(owner.isCurrent(customer));
        assertEquals("shared", owner.withGateway(
                customer, gateway -> gateway.name, null));
    }

    @Test
    public void staleSameRoleLeaseCannotOperateAfterActivityRecreation() {
        ProcessSerialGatewayOwner<FakeGateway> owner =
                newOwner();
        ProcessSerialGatewayOwner.Lease<FakeGateway> oldAdmin = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                () -> new FakeGateway("shared"));
        ProcessSerialGatewayOwner.Lease<FakeGateway> newAdmin = owner.acquire(
                ProcessSerialGatewayOwner.Role.ADMIN,
                () -> new FakeGateway("must-not-be-created"));

        assertNull(owner.withGateway(oldAdmin, gateway -> gateway, null));
        assertFalse(owner.relinquish(oldAdmin));
        assertTrue(owner.isCurrent(newAdmin));
    }

    @Test
    public void replacementWaitsForAtomicLeaseBoundOperation() throws Exception {
        ProcessSerialGatewayOwner<FakeGateway> owner =
                newOwner();
        ProcessSerialGatewayOwner.Lease<FakeGateway> oldCustomer = owner.acquire(
                ProcessSerialGatewayOwner.Role.CUSTOMER,
                () -> new FakeGateway("shared"));
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch finishOperation = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        AtomicReference<String> operatedName = new AtomicReference<>();
        AtomicReference<ProcessSerialGatewayOwner.Lease<FakeGateway>> replacement =
                new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread operation = new Thread(() -> {
            try {
                operatedName.set(owner.withGateway(oldCustomer, gateway -> {
                    operationStarted.countDown();
                    try {
                        if (!finishOperation.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("operation was never released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return gateway.name;
                }, null));
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        });
        Thread replace = new Thread(() -> {
            replacementStarted.countDown();
            try {
                replacement.set(owner.acquire(
                        ProcessSerialGatewayOwner.Role.ADMIN,
                        () -> new FakeGateway("must-not-be-created")));
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            } finally {
                replacementFinished.countDown();
            }
        });

        operation.start();
        assertTrue(operationStarted.await(2, TimeUnit.SECONDS));
        replace.start();
        assertTrue(replacementStarted.await(2, TimeUnit.SECONDS));
        assertFalse(replacementFinished.await(100, TimeUnit.MILLISECONDS));

        finishOperation.countDown();
        operation.join(2_000L);
        replace.join(2_000L);

        assertFalse(operation.isAlive());
        assertFalse(replace.isAlive());
        if (threadFailure.get() != null) {
            throw new AssertionError(threadFailure.get());
        }
        assertEquals("shared", operatedName.get());
        assertNull(owner.withGateway(oldCustomer, gateway -> gateway.name, null));
        assertEquals("shared", owner.withGateway(
                replacement.get(), gateway -> gateway.name, null));
    }

    @Test
    public void allCallSitesReceiveOneProcessSingletonIdentity() {
        ProcessSerialGatewayOwner<FakeGateway> first =
                ProcessSerialGatewayOwner.shared();
        ProcessSerialGatewayOwner<FakeGateway> second =
                ProcessSerialGatewayOwner.shared();

        assertSame(first, second);
    }

    private static final class FakeGateway {
        final String name;

        FakeGateway(String name) {
            this.name = name;
        }
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
}
