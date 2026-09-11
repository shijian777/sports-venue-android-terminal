package com.codex.lockertest.face;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class CameraOrientationPolicyTest {
    @Test
    public void allSixteenFrontCameraTransformsAreExactAndImmutable() {
        int[] rotations = {0, 90, 180, 270};
        for (int sensor : rotations) {
            for (int display : rotations) {
                CameraOrientationPolicy.Transform transform =
                        CameraOrientationPolicy.frontCamera(sensor, display);
                assertEquals((360 - ((sensor + display) % 360)) % 360,
                        transform.previewDisplayOrientation());
                assertEquals((sensor - display + 360) % 360,
                        transform.sdkRotationDegrees());
                assertEquals(1, transform.sdkMirror());
                assertEquals((sensor - display + 360) % 360,
                        transform.jpegRotationDegrees());
                assertFalse(transform.jpegMirror());
            }
        }
    }

    @Test
    public void transformRejectsEveryNonQuarterTurnIncludingOverflowValues() {
        int[] invalid = {-360, -1, 1, 89, 91, 359, 360,
                Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int value : invalid) {
            assertIllegal(() -> CameraOrientationPolicy.frontCamera(value, 0));
            assertIllegal(() -> CameraOrientationPolicy.frontCamera(0, value));
        }
    }

    @Test
    public void transformTypeAndEveryInstanceFieldAreStructurallyImmutable() {
        assertTrue(Modifier.isFinal(CameraOrientationPolicy.Transform.class.getModifiers()));
        for (Field field : CameraOrientationPolicy.Transform.class.getDeclaredFields()) {
            assertTrue("field must be private: " + field,
                    Modifier.isPrivate(field.getModifiers()));
            assertTrue("field must be final: " + field,
                    Modifier.isFinal(field.getModifiers()));
        }
        for (Constructor<?> constructor
                : CameraOrientationPolicy.Transform.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        }
        for (Method method : CameraOrientationPolicy.Transform.class.getDeclaredMethods()) {
            assertFalse("mutable setter forbidden: " + method,
                    method.getName().startsWith("set"));
        }
    }

    @Test
    public void fpsPrefersExactThenNarrowestContainingTarget() {
        int[][] ranges = {{10000, 30000}, {15000, 20000}, {15000, 15000}};
        int[] selected = CameraOrientationPolicy.selectFpsRange(ranges);
        assertArrayEquals(new int[] {15000, 15000}, selected);
        ranges[2][0] = 2;
        assertArrayEquals(new int[] {15000, 15000}, selected);
        selected[0] = 1;
        assertArrayEquals(new int[] {15000, 15000},
                CameraOrientationPolicy.selectFpsRange(
                        new int[][] {{10000, 30000}, {15000, 15000}}));

        assertArrayEquals(new int[] {14000, 16000},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {10000, 20000}, {14000, 16000}, {14500, 16500}
                }));
    }

    @Test
    public void fpsUsesClosedIntervalDistanceAndAllDeterministicTies() {
        assertArrayEquals(new int[] {16000, 16000},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {16000, 16000}, {13000, 14000}, {16000, 17000}
                }));
        assertArrayEquals(new int[] {16000, 16000},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {16000, 17000}, {16000, 16000}
                }));
        assertArrayEquals(new int[] {13000, 14000},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {13000, 14000}, {16000, 17000}
                }));
        assertArrayEquals(new int[] {13000, 14000},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {12000, 14000}, {13000, 14000}
                }));
    }

    @Test
    public void fpsIgnoresMalformedEntriesWhenALegalRangeRemainsAndDoesNotOverflow() {
        int[][] mixed = {null, {}, {1}, {0, 15000}, {20000, 10000},
                {-1, Integer.MAX_VALUE}, {Integer.MAX_VALUE - 1, Integer.MAX_VALUE}};
        assertArrayEquals(new int[] {Integer.MAX_VALUE - 1, Integer.MAX_VALUE},
                CameraOrientationPolicy.selectFpsRange(mixed));
        assertArrayEquals(new int[] {1, 1},
                CameraOrientationPolicy.selectFpsRange(new int[][] {
                        {Integer.MAX_VALUE - 1, Integer.MAX_VALUE}, {1, 1}
                }));
    }

    @Test
    public void fpsRejectsWhenNoLegalSupportedPairExists() {
        assertIllegal(() -> CameraOrientationPolicy.selectFpsRange(null));
        assertIllegal(() -> CameraOrientationPolicy.selectFpsRange(new int[0][]));
        assertIllegal(() -> CameraOrientationPolicy.selectFpsRange(new int[][] {
                null, {}, {1}, {0, 1}, {2, 1}, {-1, -1}
        }));
    }

    @Test
    public void attemptLifecycleMakesStartupCancelFirstFrameAndStopLinearizable() {
        CameraAttemptStateMachine machine = new CameraAttemptStateMachine();
        long first = machine.beginStart();
        assertTrue(first > 0L);
        assertEquals(CameraAttemptStateMachine.State.STARTING, machine.state());
        assertTrue(machine.markPreviewActive(first));
        assertTrue(machine.isRunning());
        assertTrue(machine.mayReturnBuffer(first));
        assertEquals(CameraAttemptStateMachine.FrameClaim.FIRST,
                machine.claimFrame(first));
        assertEquals(CameraAttemptStateMachine.State.RUNNING, machine.state());
        assertFalse(machine.cancelStartup(first));
        assertEquals(CameraAttemptStateMachine.FrameClaim.NEXT,
                machine.claimFrame(first));
        assertTrue(machine.beginStop());
        assertFalse(machine.isRunning());
        assertFalse(machine.mayReturnBuffer(first));
        assertFalse(machine.beginStop());
        machine.finishStop(true);
        assertEquals(CameraAttemptStateMachine.State.IDLE, machine.state());
    }

    @Test
    public void startupCancelBeforeFirstFrameInvalidatesGenerationAndIsIdempotent() {
        CameraAttemptStateMachine machine = new CameraAttemptStateMachine();
        long first = machine.beginStart();
        assertTrue(machine.markPreviewActive(first));
        assertTrue(machine.claimSurfaceRemoval(first));
        assertFalse(machine.claimSurfaceRemoval(first));
        assertTrue(machine.cancelStartup(first));
        assertFalse(machine.cancelStartup(first));
        assertEquals(CameraAttemptStateMachine.FrameClaim.STALE,
                machine.claimFrame(first));
        assertFalse(machine.mayReturnBuffer(first));
        machine.finishStop(true);
        long second = machine.beginStart();
        assertTrue(second > first);
        assertFalse(machine.claimSurfaceRemoval(first));
        assertTrue(machine.claimSurfaceRemoval(second));
        assertFalse(machine.claimSurfaceRemoval(second));
        assertFalse(machine.markPreviewActive(first));
        assertEquals(CameraAttemptStateMachine.FrameClaim.STALE,
                machine.claimFrame(first));
        assertTrue(machine.markPreviewActive(second));
    }

    @Test
    public void startingCancelBusyAndFirstFrameRaceHaveExactlyOneWinner() throws Exception {
        CameraAttemptStateMachine starting = new CameraAttemptStateMachine();
        long startGeneration = starting.beginStart();
        assertEquals(CameraAttemptStateMachine.State.STARTING, starting.state());
        assertEquals(0L, starting.beginStart());
        assertEquals(CameraAttemptStateMachine.State.STARTING, starting.state());
        assertEquals(startGeneration, starting.activeGeneration());
        assertTrue(starting.cancelStartup(startGeneration));
        assertEquals(CameraAttemptStateMachine.State.STOPPING, starting.state());
        starting.finishStop(true);

        for (int attempt = 0; attempt < 100; attempt++) {
            CameraAttemptStateMachine machine = new CameraAttemptStateMachine();
            long generation = machine.beginStart();
            assertTrue(machine.markPreviewActive(generation));
            Object ownerLock = new Object();
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<CameraAttemptStateMachine.FrameClaim> frame =
                    new AtomicReference<>();
            AtomicReference<Boolean> cancelled = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread firstFrame = checkedThread(failure, () -> {
                entered.countDown(); awaitUninterruptibly(go);
                synchronized (ownerLock) { frame.set(machine.claimFrame(generation)); }
            });
            Thread cancel = checkedThread(failure, () -> {
                entered.countDown(); awaitUninterruptibly(go);
                synchronized (ownerLock) {
                    cancelled.set(machine.cancelStartup(generation));
                }
            });
            firstFrame.start(); cancel.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            go.countDown();
            firstFrame.join(1000L); cancel.join(1000L);
            rethrow(failure.get());
            if (frame.get() == CameraAttemptStateMachine.FrameClaim.FIRST) {
                assertFalse(cancelled.get());
                assertEquals(CameraAttemptStateMachine.State.RUNNING, machine.state());
                assertTrue(machine.beginStop());
            } else {
                assertEquals(CameraAttemptStateMachine.FrameClaim.STALE, frame.get());
                assertTrue(cancelled.get());
                assertEquals(CameraAttemptStateMachine.State.STOPPING, machine.state());
            }
            machine.finishStop(true);
            assertTrue(machine.beginStart() > generation);
        }
    }

    @Test
    public void failedReleaseAndGenerationOverflowFailClosed() {
        CameraAttemptStateMachine failed = new CameraAttemptStateMachine();
        assertTrue(failed.beginStart() > 0L);
        assertTrue(failed.beginStop());
        failed.finishStop(false);
        assertEquals(CameraAttemptStateMachine.State.CLOSED, failed.state());
        assertEquals(0L, failed.beginStart());

        CameraAttemptStateMachine overflow =
                new CameraAttemptStateMachine(Long.MAX_VALUE);
        assertEquals(0L, overflow.beginStart());
        assertEquals(CameraAttemptStateMachine.State.CLOSED, overflow.state());
    }

    @Test
    public void closeForbidsStartsAndRejectsEveryStaleTransition() {
        CameraAttemptStateMachine machine = new CameraAttemptStateMachine();
        long generation = machine.beginStart();
        machine.close();
        assertEquals(CameraAttemptStateMachine.State.CLOSED, machine.state());
        assertEquals(0L, machine.beginStart());
        assertFalse(machine.markPreviewActive(generation));
        assertFalse(machine.cancelStartup(generation));
        assertEquals(CameraAttemptStateMachine.FrameClaim.STALE,
                machine.claimFrame(generation));
    }

    @Test
    public void halCleanupWaitsForInFlightOwnerCallAndRunsExactlyOnce() throws Exception {
        CameraHalGate gate = new CameraHalGate();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch permitOwnerExit = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger releases = new AtomicInteger();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch emergencyEntered = new CountDownLatch(1);
        Thread owner = checkedThread(failure, () -> gate.ownerCall(7L, () -> {
            order.add("owner-enter");
            ownerEntered.countDown();
            awaitUninterruptibly(permitOwnerExit);
            order.add("owner-exit");
        }));
        Thread emergency = checkedThread(failure, () -> {
            emergencyEntered.countDown();
            gate.cleanupOnce(7L, () -> {
                order.add("cleanup");
                releases.incrementAndGet();
            });
        });
        owner.start();
        assertTrue(ownerEntered.await(1, TimeUnit.SECONDS));
        emergency.start();
        assertTrue(emergencyEntered.await(1, TimeUnit.SECONDS));
        waitUntil(() -> gate.waiterCountForTest() == 1);
        assertEquals(0, releases.get());
        permitOwnerExit.countDown();
        owner.join(1000L);
        emergency.join(1000L);
        assertEquals(1, releases.get());
        assertEquals("owner-enter", order.get(0));
        assertEquals("owner-exit", order.get(1));
        assertEquals("cleanup", order.get(2));
        assertFalse(gate.cleanupOnce(7L, releases::incrementAndGet));
        assertFalse(gate.ownerCall(7L, releases::incrementAndGet));
        assertEquals(1, releases.get());
        rethrow(failure.get());
    }

    @Test
    public void halGateReopensForNextGenerationAndConcurrentCleanupHasOneWinner()
            throws Exception {
        CameraHalGate reusable = new CameraHalGate();
        AtomicInteger sequentialCalls = new AtomicInteger();
        assertTrue(reusable.ownerCall(7L, sequentialCalls::incrementAndGet));
        assertTrue(reusable.cleanupOnce(7L, sequentialCalls::incrementAndGet));
        assertFalse(reusable.ownerCall(7L, sequentialCalls::incrementAndGet));
        assertTrue(reusable.ownerCall(8L, sequentialCalls::incrementAndGet));
        assertTrue(reusable.cleanupOnce(8L, sequentialCalls::incrementAndGet));
        assertEquals(4, sequentialCalls.get());

        CameraHalGate concurrent = new CameraHalGate();
        assertTrue(concurrent.ownerCall(11L, () -> { }));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<Boolean> firstResult = new AtomicReference<>();
        AtomicReference<Boolean> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> childFailure = new AtomicReference<>();
        Thread first = checkedThread(childFailure, () -> {
            entered.countDown();
            awaitUninterruptibly(go);
            firstResult.set(concurrent.cleanupOnce(11L, cleanups::incrementAndGet));
        });
        Thread second = checkedThread(childFailure, () -> {
            entered.countDown();
            awaitUninterruptibly(go);
            secondResult.set(concurrent.cleanupOnce(11L, cleanups::incrementAndGet));
        });
        first.start();
        second.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        go.countDown();
        first.join(1000L);
        second.join(1000L);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        rethrow(childFailure.get());
        assertEquals(1, cleanups.get());
        assertTrue(firstResult.get() ^ secondResult.get());
    }

    @Test
    public void uninterruptibleBarrierRestoresCallerInterruptStatus() throws Exception {
        CameraHalGate gate = new CameraHalGate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = checkedThread(failure, () -> gate.ownerCall(9L, () -> {
            entered.countDown();
            awaitUninterruptibly(release);
        }));
        AtomicBoolean restored = new AtomicBoolean();
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        Thread cleanup = checkedThread(failure, () -> {
            cleanupEntered.countDown();
            gate.cleanupOnce(9L, () -> { });
            restored.set(Thread.currentThread().isInterrupted());
        });
        owner.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cleanup.start();
        assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS));
        waitUntil(() -> gate.waiterCountForTest() == 1);
        cleanup.interrupt();
        assertTrue(cleanup.isAlive());
        release.countDown();
        cleanup.join(1000L);
        owner.join(1000L);
        assertTrue(restored.get());
        rethrow(failure.get());
    }

    private static void assertIllegal(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static Thread checkedThread(AtomicReference<Throwable> failure,
            Runnable operation) {
        return new Thread(() -> {
            try { operation.run(); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
    }

    private static void waitUntil(Check check) throws Exception {
        for (int i = 0; i < 100000 && !check.ok(); i++) Thread.yield();
        assertTrue("condition was not reached", check.ok());
    }

    private interface Check { boolean ok(); }

    private static void rethrow(Throwable failure) {
        if (failure != null) throw new AssertionError(failure);
    }
}
