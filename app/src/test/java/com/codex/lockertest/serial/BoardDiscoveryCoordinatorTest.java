package com.codex.lockertest.serial;

import com.codex.lockertest.model.LockerZone;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BoardDiscoveryCoordinatorTest {
    @Test
    public void startEnsuresConnectionAndRejectsASecondActiveScan() {
        Fixture fixture = new Fixture();

        long scanId = fixture.coordinator.start();

        assertTrue(scanId > 0L);
        assertEquals(0L, fixture.coordinator.start());
        assertEquals(1, fixture.serial.ensureCalls);
        assertEquals(Long.valueOf(scanId), fixture.serial.ensureScanIds.get(0));
        assertEquals(0, fixture.serial.sent.size());
        assertEquals(0, fixture.listener.terminalCount());
    }

    @Test
    public void connectedScanWaitsForACompleteQuietWindowAndAnyBytesRestartIt() {
        Fixture fixture = new Fixture();
        long scanId = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(scanId);

        fixture.scheduler.advanceBy(299L);
        assertEquals(0, fixture.serial.sent.size());
        fixture.coordinator.onSerialBytes(scanId, bytes(0x55));
        fixture.scheduler.advanceBy(299L);
        assertEquals(0, fixture.serial.sent.size());

        fixture.scheduler.advanceBy(1L);

        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(query(1), fixture.serial.sent.get(0));
    }

    @Test
    public void responseBeforeMatchingSentIsBufferedThenTheNextBoardWaitsTwentyMillis() {
        Fixture fixture = firstQueryFixture();

        fixture.coordinator.onSerialBytes(fixture.scanId, bytes(0x80, 0x01));
        fixture.coordinator.onSerialBytes(fixture.scanId, bytes(0x01, 0x00, 0x80));
        fixture.scheduler.advanceBy(999L);
        assertEquals(1, fixture.serial.sent.size());
        assertEquals(0, fixture.listener.terminalCount());

        fixture.coordinator.onSerialSent(fixture.scanId, query(1));
        fixture.scheduler.advanceBy(19L);
        assertEquals(1, fixture.serial.sent.size());

        fixture.scheduler.advanceBy(1L);

        assertEquals(2, fixture.serial.sent.size());
        assertArrayEquals(query(2), fixture.serial.sent.get(1));
        assertEquals(0, fixture.listener.terminalCount());
    }

    @Test
    public void synchronousMatchingSentCallbacksProgressWithoutStartingTheWatchdog() {
        Fixture fixture = new Fixture();
        fixture.serial.sendHook = (scanId, payload) ->
                fixture.coordinator.onSerialSent(scanId, payload);
        fixture.scanId = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(fixture.scanId);

        fixture.scheduler.advanceBy(300L);
        fixture.scheduler.advanceBy(300L);
        fixture.scheduler.advanceBy(20L);
        fixture.scheduler.advanceBy(300L);
        fixture.scheduler.advanceBy(20L);
        fixture.scheduler.advanceBy(300L);

        assertEquals(3, fixture.serial.sent.size());
        assertEquals(Collections.singletonList(Collections.<LockerZone>emptyList()),
                fixture.listener.snapshots);
        assertEquals(0, fixture.scheduler.countScheduledDelay(1_000L));
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(1, fixture.listener.terminalCount());
    }

    @Test
    public void synchronousResponseBeforeSentIsBufferedAndCompletesAllBoards() {
        Fixture fixture = new Fixture();
        fixture.serial.sendHook = (scanId, payload) -> {
            int boardAddress = payload[1] & 0xFF;
            fixture.coordinator.onSerialBytes(
                    scanId, response(boardAddress, boardAddress == 2 ? 0x11 : 0x00));
            fixture.coordinator.onSerialSent(scanId, payload);
        };
        fixture.scanId = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(fixture.scanId);

        fixture.scheduler.advanceBy(300L);
        fixture.scheduler.advanceBy(20L);
        fixture.scheduler.advanceBy(20L);

        assertEquals(3, fixture.serial.sent.size());
        assertEquals(Arrays.asList(LockerZone.A, LockerZone.B, LockerZone.C),
                fixture.listener.lastSnapshot());
        assertEquals(0, fixture.scheduler.countScheduledDelay(1_000L));
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(1, fixture.listener.terminalCount());
    }

    @Test
    public void responseTimeoutStartsOnlyAfterMatchingSentAndUsesTheExactBoundary() {
        Fixture fixture = firstQueryFixture();

        fixture.scheduler.advanceBy(999L);
        assertEquals(1, fixture.serial.sent.size());
        fixture.coordinator.onSerialSent(fixture.scanId, query(1));
        fixture.scheduler.advanceBy(299L);
        assertEquals(1, fixture.serial.sent.size());
        assertEquals(0, fixture.listener.terminalCount());

        fixture.scheduler.advanceBy(1L);
        assertEquals(1, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(19L);
        assertEquals(1, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(1L);

        assertEquals(2, fixture.serial.sent.size());
        assertArrayEquals(query(2), fixture.serial.sent.get(1));
    }

    @Test
    public void missingOrMismatchedSentConfirmationFailsAtOneSecondWithoutRetry() {
        Fixture fixture = firstQueryFixture();

        fixture.coordinator.onSerialSent(fixture.scanId, query(2));
        fixture.coordinator.onSerialSendFailed(
                fixture.scanId, query(2), "late unrelated writer error");
        fixture.scheduler.advanceBy(999L);
        assertEquals(0, fixture.listener.terminalCount());

        fixture.scheduler.advanceBy(1L);

        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SEND),
                fixture.listener.failures);
        assertEquals(1, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(5_000L);
        fixture.coordinator.onSerialSent(fixture.scanId, query(1));
        assertEquals(1, fixture.listener.terminalCount());
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void matchingPayloadCorrelatedSendFailureTerminatesImmediately() {
        Fixture fixture = firstQueryFixture();

        fixture.coordinator.onSerialSendFailed(
                fixture.scanId, query(1), "write returned false");

        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SEND),
                fixture.listener.failures);
        assertEquals(1, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(1, fixture.listener.terminalCount());
    }

    @Test
    public void noRespondersCompletesNormallyWithOnlyThreeOrderedQueriesAndNoRetry() {
        Fixture fixture = firstQueryFixture();

        fixture.completeFromFirstQuery();

        assertEquals(3, fixture.serial.sent.size());
        assertArrayEquals(query(1), fixture.serial.sent.get(0));
        assertArrayEquals(query(2), fixture.serial.sent.get(1));
        assertArrayEquals(query(3), fixture.serial.sent.get(2));
        assertEquals(Collections.singletonList(Collections.<LockerZone>emptyList()),
                fixture.listener.snapshots);
        assertEquals(0, fixture.listener.failures.size());
        for (byte[] sent : fixture.serial.sent) {
            assertEquals(0x80, sent[0] & 0xFF);
        }
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(3, fixture.serial.sent.size());
        assertEquals(1, fixture.listener.terminalCount());
    }

    @Test
    public void oneTwoAndThreeRespondersProduceOnlyTheFixedOnlineZones() {
        assertEquals(Arrays.asList(LockerZone.A),
                completedSnapshot(1));
        assertEquals(Arrays.asList(LockerZone.B, LockerZone.C),
                completedSnapshot(2, 3));
        assertEquals(Arrays.asList(LockerZone.A, LockerZone.B, LockerZone.C),
                completedSnapshot(1, 2, 3));
    }

    @Test
    public void wrongAndLateBoardFramesCannotMarkTheCurrentZoneOnline() {
        Fixture fixture = firstQueryFixture();
        fixture.coordinator.onSerialSent(fixture.scanId, query(1));

        fixture.coordinator.onSerialBytes(fixture.scanId, response(2, 0x00));
        fixture.coordinator.onSerialBytes(fixture.scanId,
                bytes(0x80, 0x01, 0x01, 0x00, 0x81));
        fixture.scheduler.advanceBy(300L);
        fixture.coordinator.onSerialBytes(fixture.scanId, response(1, 0x00));
        fixture.scheduler.advanceBy(20L);
        fixture.finishCurrentBoard(2, true);
        fixture.finishCurrentBoard(3, false);

        assertEquals(Arrays.asList(LockerZone.B), fixture.listener.lastSnapshot());
        assertEquals(3, fixture.serial.sent.size());
    }

    @Test
    public void cancelAndStaleCallbacksOrCancelledTimersHaveNoLaterEffects() {
        Fixture fixture = new Fixture();
        long oldScan = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(oldScan);
        FakeScheduler.Job oldQuietTimer = fixture.scheduler.lastJob();

        fixture.coordinator.cancel();
        oldQuietTimer.runEvenIfCancelled();
        fixture.coordinator.onSerialConnected(oldScan);
        fixture.coordinator.onSerialBytes(oldScan, response(1, 0x00));
        fixture.coordinator.onSerialSent(oldScan, query(1));
        fixture.coordinator.onSerialSendFailed(oldScan, query(1), "stale");
        fixture.coordinator.onSerialFailure(oldScan, "stale reader");
        assertEquals(0, fixture.serial.sent.size());
        assertEquals(0, fixture.listener.terminalCount());

        long replacement = fixture.coordinator.start();
        assertTrue(replacement > oldScan);
        fixture.coordinator.onSerialConnected(replacement);
        oldQuietTimer.runEvenIfCancelled();
        assertEquals(0, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(300L);
        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(query(1), fixture.serial.sent.get(0));
    }

    @Test
    public void throwingTimerCancellationCannotPreventExplicitCancelOrAllowLateEffects() {
        Fixture fixture = new Fixture();
        long oldScan = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(oldScan);
        FakeScheduler.Job oldQuietTimer = fixture.scheduler.lastJob();
        oldQuietTimer.cancelFailure = new IllegalStateException("scheduler stopped");

        try {
            fixture.coordinator.cancel();
        } catch (RuntimeException exception) {
            fail("Timer cancellation must not escape coordinator.cancel()");
        }
        oldQuietTimer.runEvenIfCancelled();
        fixture.coordinator.onSerialConnected(oldScan);
        fixture.coordinator.onSerialFailure(oldScan, "stale");

        assertEquals(0, fixture.serial.sent.size());
        assertEquals(0, fixture.listener.terminalCount());
        long replacement = fixture.coordinator.start();
        assertTrue(replacement > oldScan);
    }

    @Test
    public void throwingTimerCancellationCannotPreventTerminalFailureCleanup() {
        Fixture fixture = firstQueryFixture();
        fixture.coordinator.onSerialSent(fixture.scanId, query(1));
        FakeScheduler.Job oldResponseTimer = fixture.scheduler.lastJob();
        oldResponseTimer.cancelFailure = new IllegalStateException("scheduler stopped");

        try {
            fixture.coordinator.onSerialFailure(fixture.scanId, "reader stopped");
        } catch (RuntimeException exception) {
            fail("Timer cancellation must not escape terminal cleanup");
        }
        oldResponseTimer.runEvenIfCancelled();

        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SERIAL),
                fixture.listener.failures);
        assertEquals(1, fixture.listener.terminalCount());
        assertTrue(fixture.coordinator.start() > fixture.scanId);
    }

    @Test
    public void openSendAndAsynchronousSerialFailuresTerminateOnceWithSafeCategories() {
        Fixture thrownOpen = new Fixture();
        thrownOpen.serial.ensureFailure = new IllegalStateException("/dev/ttyS0 denied");
        assertTrue(thrownOpen.coordinator.start() > 0L);
        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.CONNECTION),
                thrownOpen.listener.failures);

        Fixture openCallback = new Fixture();
        long openScan = openCallback.coordinator.start();
        openCallback.coordinator.onSerialOpenFailed(openScan, "native EACCES");
        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.CONNECTION),
                openCallback.listener.failures);

        Fixture rejectedSend = new Fixture();
        rejectedSend.serial.acceptSend = false;
        long rejectedScan = rejectedSend.coordinator.start();
        rejectedSend.coordinator.onSerialConnected(rejectedScan);
        rejectedSend.scheduler.advanceBy(300L);
        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SEND),
                rejectedSend.listener.failures);

        Fixture thrownSend = new Fixture();
        thrownSend.serial.sendFailure = new IllegalStateException("writer stopped");
        long thrownScan = thrownSend.coordinator.start();
        thrownSend.coordinator.onSerialConnected(thrownScan);
        thrownSend.scheduler.advanceBy(300L);
        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SEND),
                thrownSend.listener.failures);

        Fixture serialFailure = firstQueryFixture();
        serialFailure.coordinator.onSerialFailure(serialFailure.scanId,
                "read failed with raw device detail");
        serialFailure.coordinator.onSerialFailure(serialFailure.scanId, "duplicate");
        serialFailure.scheduler.advanceBy(5_000L);
        assertEquals(Arrays.asList(BoardDiscoveryCoordinator.Failure.SERIAL),
                serialFailure.listener.failures);
        assertEquals(1, serialFailure.serial.sent.size());
    }

    @Test
    public void completedSnapshotIsImmutableAndDetachedFromLaterScans() {
        Fixture fixture = firstQueryFixture();
        fixture.completeFromFirstQuery(1, 2, 3);
        List<LockerZone> firstSnapshot = fixture.listener.lastSnapshot();

        try {
            firstSnapshot.add(LockerZone.A);
            fail("Snapshot must reject mutation");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable API.
        }

        fixture.scanId = fixture.coordinator.start();
        fixture.coordinator.onSerialConnected(fixture.scanId);
        fixture.scheduler.advanceBy(300L);
        fixture.completeFromFirstQuery();

        assertEquals(Arrays.asList(LockerZone.A, LockerZone.B, LockerZone.C),
                firstSnapshot);
        assertEquals(Collections.<LockerZone>emptyList(),
                fixture.listener.lastSnapshot());
        assertFalse(firstSnapshot == fixture.listener.lastSnapshot());
    }

    private static List<LockerZone> completedSnapshot(int... respondingBoards) {
        Fixture fixture = firstQueryFixture();
        fixture.completeFromFirstQuery(respondingBoards);
        return fixture.listener.lastSnapshot();
    }

    private static Fixture firstQueryFixture() {
        Fixture fixture = new Fixture();
        fixture.scanId = fixture.coordinator.start();
        assertTrue(fixture.scanId > 0L);
        fixture.coordinator.onSerialConnected(fixture.scanId);
        fixture.scheduler.advanceBy(300L);
        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(query(1), fixture.serial.sent.get(0));
        return fixture;
    }

    private static byte[] query(int boardAddress) {
        switch (boardAddress) {
            case 1:
                return bytes(0x80, 0x01, 0x01, 0x33, 0xB3);
            case 2:
                return bytes(0x80, 0x02, 0x01, 0x33, 0xB0);
            case 3:
                return bytes(0x80, 0x03, 0x01, 0x33, 0xB1);
            default:
                throw new IllegalArgumentException("unsupported fixture board");
        }
    }

    private static byte[] response(int boardAddress, int status) {
        if (status == 0x00) {
            switch (boardAddress) {
                case 1:
                    return bytes(0x80, 0x01, 0x01, 0x00, 0x80);
                case 2:
                    return bytes(0x80, 0x02, 0x01, 0x00, 0x83);
                case 3:
                    return bytes(0x80, 0x03, 0x01, 0x00, 0x82);
                default:
                    break;
            }
        }
        if (status == 0x11) {
            switch (boardAddress) {
                case 1:
                    return bytes(0x80, 0x01, 0x01, 0x11, 0x91);
                case 2:
                    return bytes(0x80, 0x02, 0x01, 0x11, 0x92);
                case 3:
                    return bytes(0x80, 0x03, 0x01, 0x11, 0x93);
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("unsupported fixture response");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static final class Fixture {
        final FakeSerialActions serial = new FakeSerialActions();
        final FakeScheduler scheduler = new FakeScheduler();
        final RecordingListener listener = new RecordingListener();
        final BoardDiscoveryCoordinator coordinator =
                new BoardDiscoveryCoordinator(serial, scheduler, listener);
        long scanId;
        int currentScanSentOffset;

        void completeFromFirstQuery(int... respondingBoards) {
            currentScanSentOffset = serial.sent.size() - 1;
            for (int boardAddress = 1; boardAddress <= 3; boardAddress++) {
                finishCurrentBoard(boardAddress, contains(respondingBoards, boardAddress));
            }
        }

        void finishCurrentBoard(int boardAddress, boolean responds) {
            assertEquals(currentScanSentOffset + boardAddress, serial.sent.size());
            assertArrayEquals(query(boardAddress),
                    serial.sent.get(currentScanSentOffset + boardAddress - 1));
            coordinator.onSerialSent(scanId, query(boardAddress));
            if (responds) {
                int status = boardAddress % 2 == 0 ? 0x11 : 0x00;
                coordinator.onSerialBytes(scanId, response(boardAddress, status));
            } else {
                scheduler.advanceBy(300L);
            }
            if (boardAddress < 3) {
                scheduler.advanceBy(20L);
            }
        }

        private static boolean contains(int[] values, int wanted) {
            for (int value : values) {
                if (value == wanted) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FakeSerialActions
            implements BoardDiscoveryCoordinator.SerialActions {
        interface SendHook {
            void onSend(long scanId, byte[] payload);
        }

        int ensureCalls;
        boolean acceptSend = true;
        RuntimeException ensureFailure;
        RuntimeException sendFailure;
        SendHook sendHook;
        final List<Long> ensureScanIds = new ArrayList<>();
        final List<Long> sentScanIds = new ArrayList<>();
        final List<byte[]> sent = new ArrayList<>();

        @Override
        public void ensureDefaultConnected(long scanId) {
            ensureCalls++;
            ensureScanIds.add(scanId);
            if (ensureFailure != null) {
                throw ensureFailure;
            }
        }

        @Override
        public boolean send(long scanId, byte[] bytes) {
            sentScanIds.add(scanId);
            sent.add(Arrays.copyOf(bytes, bytes.length));
            if (sendFailure != null) {
                throw sendFailure;
            }
            if (sendHook != null) {
                sendHook.onSend(scanId, Arrays.copyOf(bytes, bytes.length));
            }
            return acceptSend;
        }
    }

    private static final class FakeScheduler
            implements BoardDiscoveryCoordinator.Scheduler {
        long now;
        final List<Job> jobs = new ArrayList<>();

        @Override
        public BoardDiscoveryCoordinator.Cancellable schedule(
                Runnable task, long delayMillis) {
            Job job = new Job(now + delayMillis, delayMillis, task);
            jobs.add(job);
            return job;
        }

        void advanceBy(long millis) {
            long target = now + millis;
            while (true) {
                Job due = nextDueAtOrBefore(target);
                if (due == null) {
                    now = target;
                    return;
                }
                now = due.dueAt;
                due.ran = true;
                due.task.run();
            }
        }

        Job lastJob() {
            return jobs.get(jobs.size() - 1);
        }

        int countScheduledDelay(long delayMillis) {
            int count = 0;
            for (Job job : jobs) {
                if (job.delayMillis == delayMillis) {
                    count++;
                }
            }
            return count;
        }

        private Job nextDueAtOrBefore(long target) {
            Job result = null;
            for (Job job : jobs) {
                if (job.cancelled || job.ran || job.dueAt > target) {
                    continue;
                }
                if (result == null || job.dueAt < result.dueAt) {
                    result = job;
                }
            }
            return result;
        }

        static final class Job implements BoardDiscoveryCoordinator.Cancellable {
            final long dueAt;
            final long delayMillis;
            final Runnable task;
            boolean cancelled;
            boolean ran;
            RuntimeException cancelFailure;

            Job(long dueAt, long delayMillis, Runnable task) {
                this.dueAt = dueAt;
                this.delayMillis = delayMillis;
                this.task = task;
            }

            @Override
            public void cancel() {
                if (cancelFailure != null) {
                    throw cancelFailure;
                }
                cancelled = true;
            }

            void runEvenIfCancelled() {
                task.run();
            }
        }
    }

    private static final class RecordingListener
            implements BoardDiscoveryCoordinator.Listener {
        final List<List<LockerZone>> snapshots = new ArrayList<>();
        final List<BoardDiscoveryCoordinator.Failure> failures = new ArrayList<>();

        @Override
        public void onDiscoveryCompleted(long scanId, List<LockerZone> onlineZones) {
            snapshots.add(onlineZones);
        }

        @Override
        public void onDiscoveryFailed(
                long scanId, BoardDiscoveryCoordinator.Failure failure) {
            failures.add(failure);
        }

        int terminalCount() {
            return snapshots.size() + failures.size();
        }

        List<LockerZone> lastSnapshot() {
            return snapshots.get(snapshots.size() - 1);
        }
    }
}
