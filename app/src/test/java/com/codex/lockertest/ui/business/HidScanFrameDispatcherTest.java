package com.codex.lockertest.ui.business;

import com.codex.lockertest.ui.IdCardScanSession;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HidScanFrameDispatcherTest {
    @Test
    public void wrongDeviceEmptyTerminatorDoesNotCancelTheActiveDeviceIdleFrame() {
        Fixture fixture = new Fixture(4096);

        fixture.dispatcher.key(11, 'Q', false, 2);
        fixture.dispatcher.key(22, 0, true, 4);
        fixture.scheduler.runPending();

        assertEquals(1, fixture.frames.size());
        assertFrame(fixture.frames.get(0), "Q", 11, 2, true);
    }

    @Test
    public void interleavedDeviceEventsCannotStealOrAbortTheBoundFrame() {
        Fixture fixture = new Fixture(4096);

        fixture.dispatcher.key(11, 'A', false, 2);
        fixture.dispatcher.key(22, 'B', false, 4);
        fixture.dispatcher.key(11, 'C', false, 2);
        fixture.dispatcher.key(22, 0, true, 4);
        fixture.scheduler.runPending();

        assertEquals(1, fixture.frames.size());
        assertFrame(fixture.frames.get(0), "AC", 11, 2, true);
    }

    @Test
    public void explicitTerminatorAndStaleIdleSubmitALongQrExactlyOnce() {
        Fixture fixture = new Fixture(4096);
        StringBuilder qr = new StringBuilder(4096);
        for (int index = 0; index < 4096; index++) {
            char character = (char) ('!' + (index % 94));
            qr.append(character);
            fixture.dispatcher.key(7, character, false, 2);
        }
        Runnable staleIdle = fixture.scheduler.latestTask();

        fixture.dispatcher.key(7, 0, true, 2);
        staleIdle.run();
        fixture.dispatcher.key(7, 0, true, 2);

        assertEquals(1, fixture.frames.size());
        assertFrame(fixture.frames.get(0), qr.toString(), 7, 2, true);
    }

    @Test
    public void resetCancelsPendingFrameAndInvalidatesItsStaleCallback() {
        Fixture fixture = new Fixture(4096);
        fixture.dispatcher.key(5, 'S', false, 4);
        fixture.dispatcher.key(5, 'E', false, 4);
        Runnable staleIdle = fixture.scheduler.latestTask();

        fixture.dispatcher.reset();
        staleIdle.run();

        assertTrue(fixture.frames.isEmpty());
        assertFalse(fixture.scheduler.hasPending());

        fixture.dispatcher.key(5, 'N', false, 4);
        fixture.dispatcher.key(5, 0, true, 4);
        assertEquals(1, fixture.frames.size());
        assertFrame(fixture.frames.get(0), "N", 5, 4, true);
    }

    private static void assertFrame(Frame frame, String value, int deviceId, int type, boolean valid) {
        assertEquals(value, frame.completion.value());
        assertEquals(deviceId, frame.completion.deviceId());
        assertEquals(type, frame.type);
        assertEquals(valid, frame.completion.isValidFrame());
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final List<Frame> frames = new ArrayList<>();
        final HidScanFrameDispatcher dispatcher;

        Fixture(int maximumLength) {
            dispatcher = new HidScanFrameDispatcher(maximumLength, scheduler,
                    (completion, type) -> frames.add(new Frame(completion, type)));
        }
    }

    private static final class Frame {
        final IdCardScanSession.Completion completion;
        final int type;

        Frame(IdCardScanSession.Completion completion, int type) {
            this.completion = completion;
            this.type = type;
        }
    }

    private static final class FakeScheduler implements HidScanFrameDispatcher.Scheduler {
        private final List<Runnable> tasks = new ArrayList<>();
        private final Set<Runnable> cancelled = new HashSet<>();

        @Override public void postDelayed(Runnable task, long delayMillis) {
            tasks.add(task);
        }

        @Override public void removeCallbacks(Runnable task) {
            cancelled.add(task);
        }

        Runnable latestTask() {
            return tasks.get(tasks.size() - 1);
        }

        boolean hasPending() {
            for (Runnable task : tasks) {
                if (!cancelled.contains(task)) return true;
            }
            return false;
        }

        void runPending() {
            List<Runnable> snapshot = new ArrayList<>(tasks);
            for (Runnable task : snapshot) {
                if (!cancelled.contains(task)) task.run();
            }
        }
    }
}
