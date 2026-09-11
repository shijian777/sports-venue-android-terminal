package com.codex.lockertest.serial;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CloseReopenCoordinatorTest {
    @Test
    public void readerTimeoutReportsFailureAndLeavesReopenBlocked() {
        SerialSessionState state = openedState();
        CloseReopenCoordinator coordinator = new CloseReopenCoordinator(state);
        FakeDetachedResources resources = new FakeDetachedResources(false);
        long closeToken = coordinator.beginClose();

        CloseReopenCoordinator.Result result =
                coordinator.finishClose(closeToken, resources);

        assertEquals(CloseReopenCoordinator.Result.READER_STILL_RUNNING, result);
        assertEquals(Arrays.asList("close", "await"), resources.calls);
        assertFalse(state.beginOpen());
        assertFalse(state.canSend());
    }

    @Test
    public void readerTerminationCompletesCloseBeforeReopenAndRejectsStaleFinish() {
        SerialSessionState state = openedState();
        CloseReopenCoordinator coordinator = new CloseReopenCoordinator(state);
        FakeDetachedResources resources = new FakeDetachedResources(true);
        long closeToken = coordinator.beginClose();
        AtomicBoolean callbackRanBeforeReopen = new AtomicBoolean();

        assertEquals(CloseReopenCoordinator.Result.CLOSED,
                coordinator.finishClose(closeToken, resources, () -> {
                    assertFalse(state.beginOpen());
                    callbackRanBeforeReopen.set(true);
                }));
        assertEquals(Arrays.asList("close", "await"), resources.calls);
        assertTrue(callbackRanBeforeReopen.get());
        assertTrue(state.beginOpen());
        assertTrue(state.markOpened());

        FakeDetachedResources staleResources = new FakeDetachedResources(true);
        assertEquals(CloseReopenCoordinator.Result.STALE,
                coordinator.finishClose(closeToken, staleResources));
        assertEquals(Arrays.asList("close", "await"), staleResources.calls);
        assertTrue(state.canSend());
    }

    @Test
    public void closeFailureIsConservativeAndDoesNotAwaitOrPermitReopen() {
        SerialSessionState state = openedState();
        CloseReopenCoordinator coordinator = new CloseReopenCoordinator(state);
        FakeDetachedResources resources = new FakeDetachedResources(true);
        resources.throwOnClose = true;
        long closeToken = coordinator.beginClose();

        assertEquals(CloseReopenCoordinator.Result.READER_STILL_RUNNING,
                coordinator.finishClose(closeToken, resources));
        assertEquals(Arrays.asList("close"), resources.calls);
        assertFalse(state.beginOpen());
    }

    @Test
    public void postCloseNotificationObservesClosedStateAndCanStartReopen() {
        SerialSessionState state = openedState();
        CloseReopenCoordinator coordinator = new CloseReopenCoordinator(state);
        FakeDetachedResources resources = new FakeDetachedResources(true);
        long closeToken = coordinator.beginClose();
        AtomicReference<SerialSessionState.Phase> observed = new AtomicReference<>();

        assertEquals(CloseReopenCoordinator.Result.CLOSED,
                coordinator.finishCloseThen(closeToken, resources, () -> {
                    observed.set(state.phase());
                    assertTrue(state.beginOpen());
                }));

        assertEquals(SerialSessionState.Phase.CLOSED, observed.get());
        assertEquals(SerialSessionState.Phase.OPENING, state.phase());
    }

    private static SerialSessionState openedState() {
        SerialSessionState state = new SerialSessionState();
        assertTrue(state.beginOpen());
        assertTrue(state.markOpened());
        return state;
    }

    private static final class FakeDetachedResources
            implements CloseReopenCoordinator.DetachedResources {
        final List<String> calls = new ArrayList<>();
        final boolean terminated;
        boolean throwOnClose;

        FakeDetachedResources(boolean terminated) {
            this.terminated = terminated;
        }

        @Override
        public void closeAndInterrupt() {
            calls.add("close");
            if (throwOnClose) {
                throw new IllegalStateException("close failed");
            }
        }

        @Override
        public boolean awaitTermination() {
            calls.add("await");
            return terminated;
        }
    }
}
