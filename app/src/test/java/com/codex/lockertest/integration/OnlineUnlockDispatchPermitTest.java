package com.codex.lockertest.integration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class OnlineUnlockDispatchPermitTest {
    private static final byte[] A1_UNLOCK = hex(0x8A, 0x01, 0x01, 0x11, 0x9B);
    private static final byte[] A1_SUCCESS = hex(0x8A, 0x01, 0x01, 0x00, 0x8A);
    private static final byte[] A1_FAILURE = hex(0x8A, 0x01, 0x01, 0x11, 0x9B);

    @Test
    public void matchingAuthorizedBytesWriteOnceWithoutMutatingCallerPayload()
            throws Throwable {
        byte[] requestCommand = A1_UNLOCK.clone();
        OnlineUnlockDispatchPermit permit = permit(requestCommand);
        requestCommand[0] = 0x00;
        byte[] callerPayload = A1_UNLOCK.clone();
        AtomicInteger writes = new AtomicInteger();

        assertTrue(permit.write(callerPayload, () -> true, writes::incrementAndGet));

        assertArrayEquals(A1_UNLOCK, callerPayload);
        assertEquals(1, writes.get());
        assertTrue(permit.active());
    }

    @Test
    public void mismatchedBytesAreRefusedWithoutCallingWriter() throws Throwable {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        AtomicInteger writes = new AtomicInteger();

        assertFalse(permit.write(
                hex(0x8A, 0x01, 0x02, 0x11, 0x98),
                () -> true,
                writes::incrementAndGet));

        assertEquals(0, writes.get());
        assertTrue(permit.active());
    }

    @Test
    public void matchingBytesCanBeConsumedOnlyOnce() throws Throwable {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        AtomicInteger writes = new AtomicInteger();

        assertTrue(permit.write(A1_UNLOCK, () -> true, writes::incrementAndGet));
        assertFalse(permit.write(A1_UNLOCK, () -> true, writes::incrementAndGet));

        assertEquals(1, writes.get());
        assertTrue(permit.active());
    }

    @Test
    public void readinessRevokedAfterQueueingRefusesActualWrite() throws Throwable {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        AtomicBoolean ready = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean accepted = new AtomicBoolean(true);
        Runnable queued = () -> {
            try {
                accepted.set(permit.write(A1_UNLOCK, ready::get, writes::incrementAndGet));
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        };

        ready.set(false);
        queued.run();

        assertFalse(accepted.get());
        assertEquals(0, writes.get());
        assertTrue(permit.active());
    }

    @Test
    public void cancellationAfterQueueingRefusesActualWrite() throws Throwable {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean accepted = new AtomicBoolean(true);
        Runnable queued = () -> {
            try {
                accepted.set(permit.write(A1_UNLOCK, () -> true, writes::incrementAndGet));
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        };

        permit.cancel();
        queued.run();

        assertFalse(accepted.get());
        assertEquals(0, writes.get());
        assertFalse(permit.active());
    }

    @Test
    public void writerFailureConsumesPermissionAndCannotBeRetried() throws Throwable {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        AtomicInteger writes = new AtomicInteger();
        RuntimeException expected = new RuntimeException("writer failed");

        try {
            permit.write(A1_UNLOCK, () -> true, () -> {
                writes.incrementAndGet();
                throw expected;
            });
            fail("writer failure must escape");
        } catch (RuntimeException actual) {
            assertTrue(actual == expected);
        }

        assertFalse(permit.write(A1_UNLOCK, () -> true, writes::incrementAndGet));
        assertEquals(1, writes.get());
    }

    @Test
    public void cancellationWaitsForActualWriteCriticalSection() throws Exception {
        OnlineUnlockDispatchPermit permit = permit(A1_UNLOCK);
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch cancellationAttempted = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                permit.write(A1_UNLOCK, () -> true, () -> {
                    writerEntered.countDown();
                    if (!releaseWriter.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("writer release timed out");
                    }
                });
            } catch (Throwable failure) {
                writeFailure.set(failure);
            }
        }, "online-unlock-permit-writer");
        Thread canceller = new Thread(() -> {
            cancellationAttempted.countDown();
            permit.cancel();
            cancellationReturned.countDown();
        }, "online-unlock-permit-canceller");

        writer.start();
        assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
        canceller.start();
        assertTrue(cancellationAttempted.await(5, TimeUnit.SECONDS));
        assertFalse(cancellationReturned.await(100, TimeUnit.MILLISECONDS));

        releaseWriter.countDown();
        writer.join(5_000L);
        canceller.join(5_000L);

        assertFalse(writer.isAlive());
        assertFalse(canceller.isAlive());
        assertTrue(writeFailure.get() == null);
        assertEquals(0L, cancellationReturned.getCount());
        assertFalse(permit.active());
    }

    private static OnlineUnlockDispatchPermit permit(byte[] command) {
        LockerTarget target = new LockerTarget(
                LockerZone.A,
                1,
                1,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
        AuthorizedUnlockRequest request = new AuthorizedUnlockRequest(
                41L,
                target,
                command,
                A1_SUCCESS,
                A1_FAILURE);
        return new OnlineUnlockDispatchPermit(request);
    }

    private static byte[] hex(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
