package com.codex.lockertest.face;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class FaceRuntimeStateMachineTest {
    @Test
    public void enumStageAndInitialSurfaceAreExact() {
        assertArrayEquals(new FaceRuntimeStateMachine.State[] {
                        FaceRuntimeStateMachine.State.UNINITIALIZED,
                        FaceRuntimeStateMachine.State.INITIALIZING,
                        FaceRuntimeStateMachine.State.READY,
                        FaceRuntimeStateMachine.State.FAILED,
                        FaceRuntimeStateMachine.State.RELEASED
                }, FaceRuntimeStateMachine.State.values());
        assertArrayEquals(new FaceRuntimeStateMachine.ModelStage[] {
                        FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL,
                        FaceRuntimeStateMachine.ModelStage.DETECTOR_MODEL,
                        FaceRuntimeStateMachine.ModelStage.QUALITY_MODELS,
                        FaceRuntimeStateMachine.ModelStage.BEST_IMAGE_MODEL
                }, FaceRuntimeStateMachine.ModelStage.values());
        assertArrayEquals(new FaceRuntimeStateMachine.Directive[] {
                        FaceRuntimeStateMachine.Directive.START_NOW,
                        FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                        FaceRuntimeStateMachine.Directive.START_AFTER_CLEANUP,
                        FaceRuntimeStateMachine.Directive.NO_OP
                }, FaceRuntimeStateMachine.Directive.values());
        assertEquals(15000L, FaceRuntimeStateMachine.INIT_TIMEOUT_MILLIS);
        assertEquals(FaceRuntimeStateMachine.State.UNINITIALIZED,
                new FaceRuntimeStateMachine().state());
    }

    @Test
    public void initializationIsSingleFlightAndUsesPositiveGeneration() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        FaceRuntimeStateMachine.StartResult duplicate = machine.startInitialize();
        assertTrue(first.shouldStart());
        assertTrue(first.generation() > 0L);
        assertEquals(FaceRuntimeStateMachine.Directive.START_NOW, first.directive());
        assertTrue(first.physicalAttemptId() > 0L);
        assertFalse(duplicate.shouldStart());
        assertEquals(first.generation(), duplicate.generation());
        assertEquals(FaceRuntimeStateMachine.State.INITIALIZING, machine.state());
    }

    @Test
    public void allFourDistinctStagesCanCompleteInArbitraryOrder() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        long generation = machine.startInitialize().generation();
        assertTrue(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.BEST_IMAGE_MODEL));
        assertEquals(FaceRuntimeStateMachine.State.INITIALIZING, machine.state());
        assertTrue(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
        assertTrue(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.QUALITY_MODELS));
        assertTrue(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.DETECTOR_MODEL));
        assertEquals(FaceRuntimeStateMachine.State.READY, machine.state());
        assertFalse(machine.startInitialize().shouldStart());
    }

    @Test
    public void duplicateStaleAndWrongGenerationStageCallbacksAreIgnored() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        long generation = machine.startInitialize().generation();
        assertFalse(machine.stageSucceeded(generation - 1L,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
        assertTrue(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
        assertFalse(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
        assertEquals(FaceRuntimeStateMachine.State.INITIALIZING, machine.state());
    }

    @Test
    public void everyMatchingStageFailureFailsTheAttempt() {
        for (FaceRuntimeStateMachine.ModelStage stage
                : FaceRuntimeStateMachine.ModelStage.values()) {
            FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
            long generation = machine.startInitialize().generation();
            assertTrue(machine.stageFailed(generation, stage));
            assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
            assertFalse(machine.stageSucceeded(generation, stage));
        }
    }

    @Test
    public void timeoutFailsAndFailedRetryClaimsPreviousCleanup() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        long first = machine.startInitialize().generation();
        assertTrue(machine.timeout(first));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();
        assertTrue(retry.shouldStart());
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                retry.directive());
        assertEquals(first, retry.physicalAttemptId());
        assertTrue(retry.generation() > first);
        assertFalse(machine.stageSucceeded(first,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
    }

    @Test
    public void retryCannotAllocateNewPhysicalAttemptUntilOldCleanupCompletes() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.stageFailed(first.generation(),
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL);
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();
        assertEquals(first.physicalAttemptId(), machine.currentPhysicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                retry.directive());
        assertTrue(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));

        FaceRuntimeStateMachine.CleanupResult after =
                machine.cleanupCompleted(first.physicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.START_AFTER_CLEANUP,
                after.directive());
        assertTrue(after.physicalAttemptId() > first.physicalAttemptId());
        assertEquals(after.physicalAttemptId(), machine.currentPhysicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.cleanupCompleted(first.physicalAttemptId()).directive());
        assertEquals(after.physicalAttemptId(), machine.currentPhysicalAttemptId());
    }

    @Test
    public void releaseWinsWhileRetryCleanupBarrierIsPending() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();
        FaceRuntimeStateMachine.ReleaseResult release = machine.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                release.directive());
        assertTrue(machine.claimCleanupBarrierSubmission(retry.physicalAttemptId()));
        assertFalse(machine.claimCleanupBarrierSubmission(retry.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.RELEASED, machine.state());
        FaceRuntimeStateMachine.CleanupResult after =
                machine.cleanupCompleted(first.physicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP, after.directive());
        assertEquals(0L, machine.currentPhysicalAttemptId());
    }

    @Test
    public void duplicateBarrierCompletionAndBarrierRejectionFailClosed() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        machine.startInitialize();
        assertTrue(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));
        assertTrue(machine.cleanupBarrierRejected(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        assertFalse(machine.cleanupBarrierRejected(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.cleanupCompleted(first.physicalAttemptId()).directive());
    }

    @Test
    public void barrierSubmissionRejectionCanBeRetriedWithoutOverlappingNativeAttempts() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                retry.directive());

        assertTrue(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));
        assertTrue(machine.cleanupBarrierSubmissionRejected(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        assertEquals(first.physicalAttemptId(), machine.currentPhysicalAttemptId());

        FaceRuntimeStateMachine.StartResult secondRetry = machine.startInitialize();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                secondRetry.directive());
        assertEquals(first.physicalAttemptId(), secondRetry.physicalAttemptId());
        assertTrue(machine.claimCleanupBarrierSubmission(
                secondRetry.physicalAttemptId()));
        assertFalse(machine.cleanupBarrierSubmissionRejected(
                first.physicalAttemptId() + 1L));
    }

    @Test
    public void releasedBarrierSubmissionRejectionIsTerminalAndIdempotent() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        FaceRuntimeStateMachine.ReleaseResult release = machine.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                release.directive());
        assertTrue(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));

        assertTrue(machine.cleanupBarrierSubmissionRejected(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.RELEASED, machine.state());
        assertEquals(0L, machine.currentPhysicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.release().directive());
        assertFalse(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));
    }

    @Test
    public void attemptedNativeCleanupFailureIsTerminalAndNeverClaimsCleanupAgain() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        machine.startInitialize();

        assertTrue(machine.claimCleanupBarrierSubmission(first.physicalAttemptId()));
        assertTrue(machine.cleanupAttemptFailed(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        assertEquals(0L, machine.currentPhysicalAttemptId());
        assertFalse(machine.startInitialize().shouldStart());
        assertFalse(machine.cleanupAttemptFailed(first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.release().directive());
        assertEquals(FaceRuntimeStateMachine.State.RELEASED, machine.state());
    }

    @Test
    public void abortedRetryRestoresOldPhysicalAttemptForALaterSafeBarrier() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();

        assertTrue(machine.abortStart(retry.generation(), retry.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        assertEquals(first.physicalAttemptId(), machine.currentPhysicalAttemptId());
        FaceRuntimeStateMachine.StartResult next = machine.startInitialize();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                next.directive());
        assertEquals(first.physicalAttemptId(), next.physicalAttemptId());
    }

    @Test
    public void nullContextRetryReleaseRaceStillSubmitsExactlyOneCleanupBarrier() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();

        FaceRuntimeStateMachine.ReleaseResult release = machine.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                release.directive());
        assertFalse(machine.abortStart(retry.generation(), retry.physicalAttemptId()));
        assertTrue(machine.claimCleanupBarrierSubmission(release.physicalAttemptId()));
        assertFalse(machine.claimCleanupBarrierSubmission(retry.physicalAttemptId()));
    }

    @Test
    public void timerRejectedRetryThenReleaseCanStillSubmitCleanupBarrier() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();

        assertTrue(machine.abortStart(retry.generation(), retry.physicalAttemptId()));
        FaceRuntimeStateMachine.ReleaseResult release = machine.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                release.directive());
        assertTrue(machine.claimCleanupBarrierSubmission(release.physicalAttemptId()));
        assertFalse(machine.claimCleanupBarrierSubmission(release.physicalAttemptId()));
    }

    @Test
    public void duplicateCleanupSubmissionClaimHasExactlyOneWinner() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        machine.timeout(first.generation());
        FaceRuntimeStateMachine.StartResult retry = machine.startInitialize();

        assertTrue(machine.claimCleanupBarrierSubmission(retry.physicalAttemptId()));
        assertFalse(machine.claimCleanupBarrierSubmission(retry.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.release().directive());
    }

    @Test
    public void abortedInitialDispatchRetiresTheNeverCreatedPhysicalAttempt() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult start = machine.startInitialize();
        assertTrue(machine.abortStart(start.generation(), start.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        assertEquals(0L, machine.currentPhysicalAttemptId());
        assertEquals(FaceRuntimeStateMachine.Directive.START_NOW,
                machine.startInitialize().directive());
    }

    @Test
    public void releaseBarrierIsPlacedAfterAllInitSubmissionsWithoutSleeping()
            throws Exception {
        ExecutorService runtime = Executors.newSingleThreadExecutor();
        List<Runnable> vendorTasks = Collections.synchronizedList(
                new ArrayList<Runnable>());
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch firstInitSubmitted = new CountDownLatch(1);
        CountDownLatch continueInitSubmission = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        FaceRuntimeStateMachine.TaskQueue runtimeQueue = runtime::execute;
        FaceRuntimeStateMachine.TaskQueue vendorQueue = vendorTasks::add;
        FaceRuntimeStateMachine.RuntimeVendorDispatcher dispatcher =
                new FaceRuntimeStateMachine.RuntimeVendorDispatcher(
                        runtimeQueue, vendorQueue);

        assertTrue(dispatcher.executeRuntime(() -> {
            vendorQueue.execute(() -> order.add("init-1"));
            firstInitSubmitted.countDown();
            await(continueInitSubmission);
            vendorQueue.execute(() -> order.add("init-2"));
            vendorQueue.execute(() -> order.add("init-3"));
            vendorQueue.execute(() -> order.add("init-4"));
        }));
        firstInitSubmitted.await();
        Thread release = new Thread(() -> assertTrue(
                dispatcher.executeVendorAfterRuntime(
                        () -> order.add("barrier"), rejected::incrementAndGet)));
        release.start();
        release.join();
        continueInitSubmission.countDown();
        runtime.shutdown();
        assertTrue(runtime.awaitTermination(5L, TimeUnit.SECONDS));
        for (Runnable vendorTask : new ArrayList<Runnable>(vendorTasks)) {
            vendorTask.run();
        }

        assertEquals(Arrays.asList(
                "init-1", "init-2", "init-3", "init-4", "barrier"), order);
        assertEquals(0, rejected.get());
    }

    @Test
    public void runtimeAndVendorLinkageRejectionAreFiniteAndDoNotEscape() {
        FaceRuntimeStateMachine.RuntimeVendorDispatcher runtimeRejected =
                new FaceRuntimeStateMachine.RuntimeVendorDispatcher(
                        task -> { throw new UnsatisfiedLinkError(); }, Runnable::run);
        assertFalse(runtimeRejected.executeRuntime(() -> { }));

        AtomicInteger rejected = new AtomicInteger();
        FaceRuntimeStateMachine.RuntimeVendorDispatcher vendorRejected =
                new FaceRuntimeStateMachine.RuntimeVendorDispatcher(
                        Runnable::run,
                        task -> { throw new UnsatisfiedLinkError(); });
        assertTrue(vendorRejected.executeVendorAfterRuntime(
                () -> { }, rejected::incrementAndGet));
        assertEquals(1, rejected.get());
    }

    @Test
    public void orderedDrainQueueHasOneDrainerAndPreservesConcurrentReentrantFifo()
            throws Exception {
        Object ownerLock = new Object();
        FaceRuntimeStateMachine.OrderedDrainQueue<Integer> queue =
                new FaceRuntimeStateMachine.OrderedDrainQueue<Integer>(ownerLock);
        List<Integer> delivered = Collections.synchronizedList(new ArrayList<Integer>());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch continueFirst = new CountDownLatch(1);

        synchronized (ownerLock) {
            assertTrue(queue.enqueueLocked(1));
        }
        Thread drainer = new Thread(() -> queue.drain(value -> {
            delivered.add(value);
            if (value == 1) {
                firstEntered.countDown();
                await(continueFirst);
                synchronized (ownerLock) {
                    assertFalse(queue.enqueueLocked(3));
                }
            } else if (value == 2) {
                throw new UnsatisfiedLinkError("listener boundary");
            }
        }));
        drainer.start();
        await(firstEntered);
        synchronized (ownerLock) {
            assertFalse(queue.enqueueLocked(2));
        }
        continueFirst.countDown();
        drainer.join();

        assertEquals(Arrays.asList(1, 2, 3), delivered);
        synchronized (ownerLock) {
            assertTrue(queue.enqueueLocked(4));
        }
        queue.drain(delivered::add);
        assertEquals(Arrays.asList(1, 2, 3, 4), delivered);
    }

    @Test
    public void readyAndAnalysisNotificationClaimsLoseWhenReleaseEpochWins()
            throws Exception {
        assertReleaseWinsNotificationClaim();
        assertReleaseWinsNotificationClaim();
    }

    @Test
    public void notificationClaimThatLinearizesFirstMayFinishExactlyOnce()
            throws Exception {
        FaceRuntimeStateMachine.CallbackEpoch epoch =
                new FaceRuntimeStateMachine.CallbackEpoch();
        long captured = epoch.capture();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch mayRelease = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean();
        Thread callback = new Thread(() -> {
            delivered.set(epoch.tryClaim(captured));
            claimed.countDown();
            await(mayRelease);
        });
        Thread release = new Thread(() -> {
            await(claimed);
            epoch.invalidate();
            mayRelease.countDown();
        });
        callback.start();
        release.start();
        callback.join();
        release.join();
        assertTrue(delivered.get());
        assertFalse(epoch.tryClaim(captured));
    }

    @Test
    public void operationThatBeginsAfterReleaseCapturesTheNewFiniteEpoch() {
        FaceRuntimeStateMachine.CallbackEpoch epoch =
                new FaceRuntimeStateMachine.CallbackEpoch();
        epoch.invalidate();
        long postReleaseOperation = epoch.capture();
        assertTrue(epoch.tryClaim(postReleaseOperation));
    }

    @Test
    public void closedCallbackFenceRejectsEveryLaterDelivery() {
        FaceRuntimeStateMachine.CallbackDeliveryFence fence =
                new FaceRuntimeStateMachine.CallbackDeliveryFence();
        AtomicInteger calls = new AtomicInteger();

        fence.beginClose();
        fence.awaitDrained();

        assertFalse(fence.deliver(calls::incrementAndGet));
        assertEquals(0, calls.get());
    }

    @Test
    public void externalCloseWaitsUntilAnEnteredCallbackReturns() throws Exception {
        FaceRuntimeStateMachine.CallbackDeliveryFence fence =
                new FaceRuntimeStateMachine.CallbackDeliveryFence();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread callback = new Thread(() -> fence.deliver(() -> {
            calls.incrementAndGet();
            callbackEntered.countDown();
            await(allowCallbackReturn);
        }));
        Thread close = new Thread(() -> {
            await(callbackEntered);
            fence.beginClose();
            fence.awaitDrained();
            closeReturned.countDown();
        });

        callback.start();
        close.start();
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
        allowCallbackReturn.countDown();
        callback.join();
        close.join();

        assertEquals(1, calls.get());
        assertEquals(0L, closeReturned.getCount());
        assertFalse(fence.deliver(calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test
    public void callbackCanCloseAndDrainItsOwnFenceWithoutDeadlock() throws Exception {
        FaceRuntimeStateMachine.CallbackDeliveryFence fence =
                new FaceRuntimeStateMachine.CallbackDeliveryFence();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Thread callback = new Thread(() -> fence.deliver(() -> {
            calls.incrementAndGet();
            fence.beginClose();
            fence.awaitDrained();
            returned.set(true);
        }));
        callback.setDaemon(true);

        callback.start();
        callback.join(1000L);

        assertFalse("reentrant close must not deadlock", callback.isAlive());
        assertTrue(returned.get());
        assertEquals(1, calls.get());
        assertFalse(fence.deliver(calls::incrementAndGet));
    }

    @Test
    public void runningCancelWaitsForCleanupAndSuppressesNotification()
            throws Exception {
        CountDownLatch waiterEntered = new CountDownLatch(1);
        FaceRuntimeStateMachine.AnalysisGate gate =
                new FaceRuntimeStateMachine.AnalysisGate(waiterEntered::countDown);
        CountDownLatch nativeEntered = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        CountDownLatch cancelReturned = new CountDownLatch(1);
        AtomicBoolean notification = new AtomicBoolean(true);
        AtomicInteger cleanupClaims = new AtomicInteger();
        Thread worker = new Thread(() -> {
            assertEquals(FaceRuntimeStateMachine.AnalysisStart.RUN_NATIVE,
                    gate.tryStart());
            nativeEntered.countDown();
            await(allowCleanup);
            if (gate.completeCleanup()) cleanupClaims.incrementAndGet();
            notification.set(gate.tryClaimNotification());
        });
        worker.start();
        nativeEntered.await();
        Thread cancel = new Thread(() -> {
            gate.cancel();
            gate.awaitCleanup();
            cancelReturned.countDown();
        });
        cancel.start();
        waiterEntered.await();
        assertEquals(1L, cancelReturned.getCount());
        allowCleanup.countDown();
        worker.join();
        cancel.join();

        assertEquals(0L, cancelReturned.getCount());
        assertEquals(1, cleanupClaims.get());
        assertFalse(notification.get());
        assertFalse(gate.completeCleanup());
    }

    @Test
    public void multipleCancelsWaitForOneCleanupAndRunnerNeverWaitsOnItself()
            throws Exception {
        CountDownLatch waiters = new CountDownLatch(2);
        FaceRuntimeStateMachine.AnalysisGate gate =
                new FaceRuntimeStateMachine.AnalysisGate(waiters::countDown);
        assertEquals(FaceRuntimeStateMachine.AnalysisStart.RUN_NATIVE, gate.tryStart());
        Thread first = new Thread(() -> { gate.cancel(); gate.awaitCleanup(); });
        Thread second = new Thread(() -> { gate.cancel(); gate.awaitCleanup(); });
        first.start();
        second.start();
        waiters.await();
        assertTrue(gate.completeCleanup());
        first.join();
        second.join();
        assertFalse(gate.tryClaimNotification());

        FaceRuntimeStateMachine.AnalysisGate runner =
                new FaceRuntimeStateMachine.AnalysisGate();
        assertEquals(FaceRuntimeStateMachine.AnalysisStart.RUN_NATIVE,
                runner.tryStart());
        runner.cancel();
        runner.awaitCleanup();
        assertTrue(runner.completeCleanup());
    }

    @Test
    public void startDispatchRejectionFailsClosedAndReleaseClaimsOneCleanup() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        FaceRuntimeStateMachine.StartResult first = machine.startInitialize();
        assertTrue(machine.startDispatchRejected(
                first.generation(), first.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
        FaceRuntimeStateMachine.ReleaseResult release = machine.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                release.directive());
        assertEquals(first.physicalAttemptId(), release.physicalAttemptId());
        assertTrue(machine.claimCleanupBarrierSubmission(release.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                machine.release().directive());
    }

    @Test
    public void lastSuccessAndTimeoutRaceHasExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
            long generation = machine.startInitialize().generation();
            machine.stageSucceeded(generation,
                    FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL);
            machine.stageSucceeded(generation,
                    FaceRuntimeStateMachine.ModelStage.DETECTOR_MODEL);
            machine.stageSucceeded(generation,
                    FaceRuntimeStateMachine.ModelStage.QUALITY_MODELS);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean successWon = new AtomicBoolean();
            AtomicBoolean timeoutWon = new AtomicBoolean();
            Thread success = new Thread(() -> {
                ready.countDown();
                await(go);
                successWon.set(machine.stageSucceeded(generation,
                        FaceRuntimeStateMachine.ModelStage.BEST_IMAGE_MODEL));
            });
            Thread timeout = new Thread(() -> {
                ready.countDown();
                await(go);
                timeoutWon.set(machine.timeout(generation));
            });
            success.start();
            timeout.start();
            ready.await();
            go.countDown();
            success.join();
            timeout.join();
            assertNotEquals(successWon.get(), timeoutWon.get());
            assertEquals(successWon.get() ? FaceRuntimeStateMachine.State.READY
                            : FaceRuntimeStateMachine.State.FAILED,
                    machine.state());
        }
    }

    @Test
    public void releaseClaimsCleanupExactlyOnceFromEveryAttemptState() {
        FaceRuntimeStateMachine uninitialized = new FaceRuntimeStateMachine();
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                uninitialized.release().directive());
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                uninitialized.release().directive());

        FaceRuntimeStateMachine initializing = new FaceRuntimeStateMachine();
        initializing.startInitialize();
        FaceRuntimeStateMachine.ReleaseResult initializingRelease = initializing.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                initializingRelease.directive());
        assertTrue(initializing.claimCleanupBarrierSubmission(
                initializingRelease.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                initializing.release().directive());

        FaceRuntimeStateMachine ready = readyMachine();
        FaceRuntimeStateMachine.ReleaseResult readyRelease = ready.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                readyRelease.directive());
        assertTrue(ready.claimCleanupBarrierSubmission(readyRelease.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                ready.release().directive());

        FaceRuntimeStateMachine failed = new FaceRuntimeStateMachine();
        long generation = failed.startInitialize().generation();
        failed.timeout(generation);
        FaceRuntimeStateMachine.ReleaseResult failedRelease = failed.release();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                failedRelease.directive());
        assertTrue(failed.claimCleanupBarrierSubmission(failedRelease.physicalAttemptId()));
        assertEquals(FaceRuntimeStateMachine.Directive.NO_OP,
                failed.release().directive());
    }

    @Test
    public void releasePermanentlyRejectsInitializeAnalyzeAndCallbacks() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        long generation = machine.startInitialize().generation();
        assertEquals(FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                machine.release().directive());
        assertEquals(FaceRuntimeStateMachine.State.RELEASED, machine.state());
        assertFalse(machine.startInitialize().shouldStart());
        assertFalse(machine.mayAnalyze());
        assertFalse(machine.stageSucceeded(generation,
                FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
        assertFalse(machine.timeout(generation));
    }

    @Test
    public void readyAllowsAnalyzeOnlyUntilRelease() {
        FaceRuntimeStateMachine machine = readyMachine();
        assertTrue(machine.mayAnalyze());
        machine.release();
        assertFalse(machine.mayAnalyze());
    }

    @Test
    public void generationOverflowFailsClosed() {
        FaceRuntimeStateMachine machine =
                new FaceRuntimeStateMachine(Long.MAX_VALUE - 1L);
        FaceRuntimeStateMachine.StartResult last = machine.startInitialize();
        assertEquals(Long.MAX_VALUE, last.generation());
        machine.timeout(last.generation());
        FaceRuntimeStateMachine.StartResult overflow = machine.startInitialize();
        assertFalse(overflow.shouldStart());
        assertEquals(0L, overflow.generation());
        assertEquals(FaceRuntimeStateMachine.State.FAILED, machine.state());
    }

    @Test
    public void processBindingConsumesExactlySixteenBytesAndReturnsLowerHex() {
        RecordingRandom random = new RecordingRandom();
        FaceProcessBinding binding = new FaceProcessBinding(random);
        random.observeClear();
        assertEquals(16, random.requestedLength);
        assertEquals("000102030405060708090a0b0c0d0e0f", binding.value());
        assertTrue(binding.value().matches("[0-9a-f]{32}"));
        assertTrue(random.callerBufferWasCleared);
    }

    @Test
    public void separateProcessBindingInstancesRemainDistinct() {
        FaceProcessBinding first = new FaceProcessBinding(new FillRandom((byte) 0x11));
        FaceProcessBinding second = new FaceProcessBinding(new FillRandom((byte) 0x22));
        assertNotEquals(first.value(), second.value());
    }

    private static FaceRuntimeStateMachine readyMachine() {
        FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
        long generation = machine.startInitialize().generation();
        for (FaceRuntimeStateMachine.ModelStage stage
                : FaceRuntimeStateMachine.ModelStage.values()) {
            machine.stageSucceeded(generation, stage);
        }
        return machine;
    }

    private static void assertReleaseWinsNotificationClaim() throws Exception {
        FaceRuntimeStateMachine.CallbackEpoch epoch =
                new FaceRuntimeStateMachine.CallbackEpoch();
        long captured = epoch.capture();
        CountDownLatch releaseDone = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean(true);
        Thread release = new Thread(() -> {
            epoch.invalidate();
            releaseDone.countDown();
        });
        Thread callback = new Thread(() -> {
            await(releaseDone);
            delivered.set(epoch.tryClaim(captured));
        });
        callback.start();
        release.start();
        callback.join();
        release.join();
        assertFalse(delivered.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class RecordingRandom extends SecureRandom {
        int requestedLength;
        byte[] delivered;
        boolean callerBufferWasCleared;

        @Override
        public void nextBytes(byte[] bytes) {
            requestedLength = bytes.length;
            delivered = bytes;
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
            }
        }

        void observeClear() {
            callerBufferWasCleared = true;
            for (byte value : delivered) {
                callerBufferWasCleared &= value == 0;
            }
        }
    }

    private static final class FillRandom extends SecureRandom {
        private final byte value;

        FillRandom(byte value) {
            this.value = value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, value);
        }
    }
}
