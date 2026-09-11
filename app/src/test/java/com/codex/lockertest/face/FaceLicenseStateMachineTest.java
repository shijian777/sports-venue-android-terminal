package com.codex.lockertest.face;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class FaceLicenseStateMachineTest {
    @Test
    public void enumSurfaceAndInitialStateAreExact() {
        assertArrayEquals(new FaceLicenseStateMachine.State[] {
                        FaceLicenseStateMachine.State.UNKNOWN,
                        FaceLicenseStateMachine.State.CHECKING_LOCAL,
                        FaceLicenseStateMachine.State.ACTIVATING_ONLINE,
                        FaceLicenseStateMachine.State.READY,
                        FaceLicenseStateMachine.State.INVALID,
                        FaceLicenseStateMachine.State.FAILED
                }, FaceLicenseStateMachine.State.values());
        assertArrayEquals(new FaceLicenseStateMachine.OperationKind[] {
                        FaceLicenseStateMachine.OperationKind.LOCAL_CHECK,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION
                }, FaceLicenseStateMachine.OperationKind.values());
        assertEquals(15000L, FaceLicenseStateMachine.OPERATION_TIMEOUT_MILLIS);
        assertEquals(FaceLicenseStateMachine.State.UNKNOWN,
                new FaceLicenseStateMachine().state());
    }

    @Test
    public void localAndCrossKindCallsShareOnePositiveFlight() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        FaceLicenseStateMachine.StartResult first = machine.startLocalCheck();
        FaceLicenseStateMachine.StartResult duplicate = machine.startLocalCheck();
        FaceLicenseStateMachine.StartResult crossKind = machine.startOnlineActivation();

        assertTrue(first.shouldStart());
        assertTrue(first.operationId() > 0L);
        assertFalse(duplicate.shouldStart());
        assertFalse(crossKind.shouldStart());
        assertEquals(first.operationId(), duplicate.operationId());
        assertEquals(first.operationId(), crossKind.operationId());
        assertEquals(FaceLicenseStateMachine.State.CHECKING_LOCAL, machine.state());
    }

    @Test
    public void onlineFlightIsAlsoSingleFlight() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        FaceLicenseStateMachine.StartResult first = machine.startOnlineActivation();
        FaceLicenseStateMachine.StartResult duplicate = machine.startOnlineActivation();
        assertTrue(first.shouldStart());
        assertFalse(duplicate.shouldStart());
        assertEquals(first.operationId(), duplicate.operationId());
        assertEquals(FaceLicenseStateMachine.State.ACTIVATING_ONLINE, machine.state());
    }

    @Test
    public void allTerminalTransitionsAreFiniteAndTyped() {
        FaceLicenseStateMachine success = new FaceLicenseStateMachine();
        long successId = success.startLocalCheck().operationId();
        assertTrue(success.completeSuccess(successId,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertEquals(FaceLicenseStateMachine.State.READY, success.state());

        FaceLicenseStateMachine invalid = new FaceLicenseStateMachine();
        long invalidId = invalid.startLocalCheck().operationId();
        assertTrue(invalid.completeInvalid(invalidId,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertEquals(FaceLicenseStateMachine.State.INVALID, invalid.state());

        FaceLicenseStateMachine failed = new FaceLicenseStateMachine();
        long failedId = failed.startOnlineActivation().operationId();
        assertTrue(failed.completeFailure(failedId,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertEquals(FaceLicenseStateMachine.State.FAILED, failed.state());

        FaceLicenseStateMachine timeout = new FaceLicenseStateMachine();
        long timeoutId = timeout.startOnlineActivation().operationId();
        assertTrue(timeout.timeout(timeoutId,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertEquals(FaceLicenseStateMachine.State.FAILED, timeout.state());
    }

    @Test
    public void readyIsIdempotentAndDoesNotStartMoreWork() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        long id = machine.startLocalCheck().operationId();
        assertTrue(machine.completeSuccess(id,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        FaceLicenseStateMachine.StartResult local = machine.startLocalCheck();
        FaceLicenseStateMachine.StartResult online = machine.startOnlineActivation();
        assertFalse(local.shouldStart());
        assertFalse(online.shouldStart());
        assertEquals(0L, local.operationId());
        assertEquals(0L, online.operationId());
        assertEquals(FaceLicenseStateMachine.State.READY, machine.state());
    }

    @Test
    public void invalidAndFailedPermitFreshExplicitRetries() {
        FaceLicenseStateMachine invalid = new FaceLicenseStateMachine();
        long invalidId = invalid.startLocalCheck().operationId();
        invalid.completeInvalid(invalidId, FaceLicenseStateMachine.OperationKind.LOCAL_CHECK);
        FaceLicenseStateMachine.StartResult retryInvalid = invalid.startOnlineActivation();
        assertTrue(retryInvalid.shouldStart());
        assertTrue(retryInvalid.operationId() > invalidId);

        FaceLicenseStateMachine failed = new FaceLicenseStateMachine();
        long failedId = failed.startOnlineActivation().operationId();
        failed.completeFailure(failedId,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION);
        FaceLicenseStateMachine.StartResult retryFailed = failed.startOnlineActivation();
        assertTrue(retryFailed.shouldStart());
        assertTrue(retryFailed.operationId() > failedId);

        FaceLicenseStateMachine failedCheck = new FaceLicenseStateMachine();
        long checkId = failedCheck.startLocalCheck().operationId();
        failedCheck.completeFailure(checkId,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK);
        assertTrue(failedCheck.startLocalCheck().shouldStart());
    }

    @Test
    public void staleWrongKindDuplicateAndLateCallbacksAreIgnored() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        long id = machine.startLocalCheck().operationId();
        assertFalse(machine.completeSuccess(id + 1L,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertFalse(machine.completeSuccess(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertEquals(FaceLicenseStateMachine.State.CHECKING_LOCAL, machine.state());
        assertTrue(machine.timeout(id, FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertFalse(machine.completeSuccess(id,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertFalse(machine.timeout(id, FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertEquals(FaceLicenseStateMachine.State.FAILED, machine.state());
    }

    @Test
    public void successAndTimeoutRaceHasExactlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
            long id = machine.startOnlineActivation().operationId();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean successWon = new AtomicBoolean();
            AtomicBoolean timeoutWon = new AtomicBoolean();
            Thread success = new Thread(() -> {
                ready.countDown();
                await(go);
                successWon.set(machine.completeSuccess(id,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
            });
            Thread timeout = new Thread(() -> {
                ready.countDown();
                await(go);
                timeoutWon.set(machine.timeout(id,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
            });
            success.start();
            timeout.start();
            ready.await();
            go.countDown();
            success.join();
            timeout.join();
            assertNotEquals(successWon.get(), timeoutWon.get());
            assertEquals(successWon.get() ? FaceLicenseStateMachine.State.READY
                            : FaceLicenseStateMachine.State.FAILED,
                    machine.state());
        }
    }

    @Test
    public void timeoutBeforeCommitPreventsTheOldOperationFromClaimingPersistence() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        long id = machine.startOnlineActivation().operationId();

        assertTrue(machine.timeout(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertFalse(machine.tryBeginCommit(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertEquals(FaceLicenseStateMachine.State.FAILED, machine.state());
    }

    @Test
    public void commitBeforeTimeoutOwnsTheFinalResultAndRejectsDuplicateClaims() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        long id = machine.startOnlineActivation().operationId();

        assertTrue(machine.tryBeginCommit(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertFalse(machine.tryBeginCommit(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertFalse(machine.timeout(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertTrue(machine.completeSuccess(id,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
        assertEquals(FaceLicenseStateMachine.State.READY, machine.state());
    }

    @Test
    public void commitAndTimeoutRaceHasExactlyOneWinnerWithoutSleeping() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
            long id = machine.startOnlineActivation().operationId();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean commitWon = new AtomicBoolean();
            AtomicBoolean timeoutWon = new AtomicBoolean();
            Thread commit = new Thread(() -> {
                ready.countDown();
                await(go);
                commitWon.set(machine.tryBeginCommit(id,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
            });
            Thread timeout = new Thread(() -> {
                ready.countDown();
                await(go);
                timeoutWon.set(machine.timeout(id,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
            });
            commit.start();
            timeout.start();
            ready.await();
            go.countDown();
            commit.join();
            timeout.join();

            assertNotEquals(commitWon.get(), timeoutWon.get());
            if (commitWon.get()) {
                assertEquals(FaceLicenseStateMachine.State.ACTIVATING_ONLINE,
                        machine.state());
                assertTrue(machine.completeFailure(id,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
            } else {
                assertEquals(FaceLicenseStateMachine.State.FAILED, machine.state());
            }
        }
    }

    @Test
    public void commitRejectsWrongKindAndStaleOperationAndLengthIsBounded() {
        FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        long id = machine.startLocalCheck().operationId();
        assertFalse(machine.tryBeginCommit(id,
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        assertFalse(machine.tryBeginCommit(id + 1L,
                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));

        assertFalse(FaceLicenseStateMachine.isActivationLengthAllowed(0));
        assertTrue(FaceLicenseStateMachine.isActivationLengthAllowed(1));
        assertTrue(FaceLicenseStateMachine.isActivationLengthAllowed(4096));
        assertFalse(FaceLicenseStateMachine.isActivationLengthAllowed(4097));
    }

    @Test
    public void operationIdOverflowFailsClosedWithoutWrapping() {
        FaceLicenseStateMachine machine =
                new FaceLicenseStateMachine(Long.MAX_VALUE - 1L);
        FaceLicenseStateMachine.StartResult last = machine.startLocalCheck();
        assertTrue(last.shouldStart());
        assertEquals(Long.MAX_VALUE, last.operationId());
        assertTrue(machine.completeFailure(last.operationId(),
                FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
        FaceLicenseStateMachine.StartResult overflow = machine.startOnlineActivation();
        assertFalse(overflow.shouldStart());
        assertEquals(0L, overflow.operationId());
        assertEquals(FaceLicenseStateMachine.State.FAILED, machine.state());
    }

    @Test
    public void pureStateMachineHasNoAndroidSchedulerListenerOrSecretFields() {
        Set<String> forbidden = new HashSet<String>(Arrays.asList(
                "context", "handler", "executor", "scheduler", "listener",
                "license", "secret", "response", "message", "device"));
        for (Field field : FaceLicenseStateMachine.class.getDeclaredFields()) {
            assertFalse("forbidden field: " + field.getName(),
                    forbidden.contains(field.getName().toLowerCase()));
            assertFalse(field.getType().getName().startsWith("android."));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
