package com.codex.lockertest.serial;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GatewayDisposalCoordinatorTest {
    @Test
    public void primaryQueueRejectionCompletesFalseWithoutRunningBarrierElsewhere() {
        FakeQueue queue = new FakeQueue();
        queue.reject = true;
        GatewayDisposalCoordinator coordinator = new GatewayDisposalCoordinator(queue);
        FakeSnapshot snapshot = new FakeSnapshot();
        List<Boolean> outcomes = new ArrayList<>();

        coordinator.dispose(() -> snapshot, outcomes::add);

        assertEquals(1, snapshot.closeCalls);
        assertEquals(0, snapshot.awaitCalls);
        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0));
    }

    @Test
    public void concurrentDisposesShareTheFirstRunningReaderSnapshotAndOutcome()
            throws Exception {
        FakeQueue queue = new FakeQueue();
        GatewayDisposalCoordinator coordinator = new GatewayDisposalCoordinator(queue);
        FakeSnapshot runningReader = new FakeSnapshot();
        runningReader.blockClose = true;
        FakeSnapshot wrongStoppedReader = new FakeSnapshot();
        List<Boolean> outcomes = new ArrayList<>();

        Thread first = new Thread(() -> coordinator.dispose(
                () -> runningReader, outcomes::add));
        first.start();
        assertTrue(runningReader.closeEntered.await(1L, TimeUnit.SECONDS));
        Thread second = new Thread(() -> coordinator.dispose(
                () -> wrongStoppedReader, outcomes::add));
        second.start();
        second.join(1_000L);
        runningReader.allowClose.countDown();
        first.join(1_000L);

        assertEquals(1, queue.tasks.size());
        assertEquals(1, runningReader.closeCalls);
        assertEquals(0, wrongStoppedReader.closeCalls);
        queue.runAll();
        assertEquals(1, runningReader.awaitCalls);
        assertEquals(0, wrongStoppedReader.awaitCalls);
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0));
        assertTrue(outcomes.get(1));
    }

    @Test
    public void releaseExceptionCompletesEveryWaiterOnceAsFailure() {
        FakeQueue queue = new FakeQueue();
        GatewayDisposalCoordinator coordinator = new GatewayDisposalCoordinator(queue);
        List<Boolean> outcomes = new ArrayList<>();
        coordinator.dispose(() -> {
            throw new IllegalStateException("detach failed");
        }, outcomes::add);
        coordinator.dispose(FakeSnapshot::new, outcomes::add);

        assertEquals(2, outcomes.size());
        assertFalse(outcomes.get(0));
        assertFalse(outcomes.get(1));
    }

    private static final class FakeQueue implements GatewayDisposalCoordinator.Queue {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject;

        @Override
        public void execute(Runnable task) {
            if (reject) {
                throw new IllegalStateException("rejected");
            }
            tasks.add(task);
        }

        @Override
        public void shutdownAfterQueuedTasks() {
        }

        void runAll() {
            for (Runnable task : new ArrayList<>(tasks)) {
                task.run();
            }
            tasks.clear();
        }
    }

    private static final class FakeSnapshot
            implements GatewayDisposalCoordinator.ResourceSnapshot {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1);
        boolean blockClose;
        int closeCalls;
        int awaitCalls;

        @Override
        public void closeAndInterrupt() {
            closeCalls++;
            closeEntered.countDown();
            if (blockClose) {
                try {
                    allowClose.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean awaitTermination() {
            awaitCalls++;
            return true;
        }
    }
}
