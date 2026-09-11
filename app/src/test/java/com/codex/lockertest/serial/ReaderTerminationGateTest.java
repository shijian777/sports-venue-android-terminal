package com.codex.lockertest.serial;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReaderTerminationGateTest {
    @Test
    public void gateDoesNotReportTerminationUntilReaderSignalsItsFinallyBlock()
            throws Exception {
        ReaderTerminationGate gate = ReaderTerminationGate.running();
        CountDownLatch waiting = new CountDownLatch(1);
        boolean[] result = {false};
        Thread barrier = new Thread(() -> {
            waiting.countDown();
            result[0] = gate.awaitStopped(2_000L);
        });
        barrier.start();
        assertTrue(waiting.await(1L, TimeUnit.SECONDS));
        assertTrue(barrier.isAlive());

        gate.signalStopped();
        barrier.join(1_000L);

        assertFalse(barrier.isAlive());
        assertTrue(result[0]);
    }

    @Test
    public void alreadyStoppedReaderCompletesImmediately() {
        assertTrue(ReaderTerminationGate.stopped().awaitStopped(1L));
    }

    @Test
    public void timeoutIsAnExplicitFailureInsteadOfAnEarlySuccess() {
        assertFalse(ReaderTerminationGate.running().awaitStopped(1L));
    }
}
