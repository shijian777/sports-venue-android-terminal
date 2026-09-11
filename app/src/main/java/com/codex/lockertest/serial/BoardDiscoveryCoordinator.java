package com.codex.lockertest.serial;

import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.BoardStatusProtocol;
import com.codex.lockertest.protocol.BoardStatusResponseDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public final class BoardDiscoveryCoordinator {
    public enum Failure {
        CONNECTION,
        SEND,
        SERIAL
    }

    public interface SerialActions {
        void ensureDefaultConnected(long scanId);

        boolean send(long scanId, byte[] bytes);
    }

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Listener {
        void onDiscoveryCompleted(long scanId, List<LockerZone> onlineZones);

        void onDiscoveryFailed(long scanId, Failure failure);

    }

    private enum Phase {
        IDLE,
        CONNECTING,
        QUIET_GUARD,
        QUEUEING,
        AWAITING_SENT,
        WAITING_RESPONSE,
        GAP
    }

    private static final long RECEIVE_QUIET_MILLIS = 300L;
    private static final long SENT_WATCHDOG_MILLIS = 1_000L;
    private static final long RESPONSE_TIMEOUT_MILLIS = 300L;
    private static final long BOARD_GAP_MILLIS = 20L;
    private static final int FIRST_BOARD_ADDRESS = 1;
    private static final int LAST_BOARD_ADDRESS = 3;

    private final SerialActions serialActions;
    private final Scheduler scheduler;
    private final Listener listener;
    private final EnumSet<LockerZone> onlineZones = EnumSet.noneOf(LockerZone.class);

    private long nextScanId;
    private long activeScanId;
    private long timerGeneration;
    private boolean active;
    private Phase phase = Phase.IDLE;
    private int currentBoardAddress;
    private byte[] currentQuery;
    private BoardStatusResponseDetector responseDetector;
    private boolean bufferedPresent;
    private boolean sentWhileQueueing;
    private Cancellable timerTask;

    public BoardDiscoveryCoordinator(
            SerialActions serialActions,
            Scheduler scheduler,
            Listener listener) {
        if (serialActions == null) {
            throw new IllegalArgumentException("serialActions cannot be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        this.serialActions = serialActions;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public synchronized long start() {
        if (active) {
            return 0L;
        }

        long scanId = nextScanId();
        active = true;
        activeScanId = scanId;
        phase = Phase.CONNECTING;
        currentBoardAddress = 0;
        currentQuery = null;
        responseDetector = null;
        bufferedPresent = false;
        sentWhileQueueing = false;
        onlineZones.clear();
        try {
            serialActions.ensureDefaultConnected(scanId);
        } catch (RuntimeException exception) {
            if (isActive(scanId)) {
                failLocked(scanId, Failure.CONNECTION);
            }
        }
        return scanId;
    }

    public synchronized void onSerialConnected(long scanId) {
        if (!isActive(scanId, Phase.CONNECTING)) {
            return;
        }
        phase = Phase.QUIET_GUARD;
        if (!scheduleLocked(scanId, RECEIVE_QUIET_MILLIS,
                () -> onQuietWindowCompleteLocked(scanId))) {
            failLocked(scanId, Failure.SERIAL);
        }
    }

    public synchronized void onSerialOpenFailed(long scanId, String detail) {
        if (isActive(scanId, Phase.CONNECTING)) {
            failLocked(scanId, Failure.CONNECTION);
        }
    }

    public synchronized void onSerialFailure(long scanId, String detail) {
        if (isActive(scanId)) {
            failLocked(scanId, Failure.SERIAL);
        }
    }

    public synchronized void onSerialSendFailed(
            long scanId, byte[] failedBytes, String detail) {
        if (!isActive(scanId)
                || (phase != Phase.QUEUEING
                && phase != Phase.AWAITING_SENT
                && phase != Phase.WAITING_RESPONSE)
                || !matchesCurrentQuery(failedBytes)) {
            return;
        }
        failLocked(scanId, Failure.SEND);
    }

    public synchronized void onSerialSent(long scanId, byte[] bytes) {
        if (!isActive(scanId) || !matchesCurrentQuery(bytes)) {
            return;
        }
        if (phase == Phase.QUEUEING) {
            sentWhileQueueing = true;
            return;
        }
        if (phase != Phase.AWAITING_SENT) {
            return;
        }

        cancelTimerLocked();
        beginResponseWindowLocked(scanId);
    }

    public synchronized void onSerialBytes(long scanId, byte[] bytes) {
        if (!isActive(scanId) || bytes == null || bytes.length == 0) {
            return;
        }

        if (phase == Phase.QUIET_GUARD) {
            if (!scheduleLocked(scanId, RECEIVE_QUIET_MILLIS,
                    () -> onQuietWindowCompleteLocked(scanId))) {
                failLocked(scanId, Failure.SERIAL);
            }
            return;
        }

        if (phase != Phase.QUEUEING
                && phase != Phase.AWAITING_SENT
                && phase != Phase.WAITING_RESPONSE) {
            return;
        }
        if (responseDetector.append(bytes, bytes.length)
                != BoardStatusResponseDetector.Result.PRESENT) {
            return;
        }
        if (phase == Phase.QUEUEING || phase == Phase.AWAITING_SENT) {
            bufferedPresent = true;
            return;
        }
        completeCurrentBoardLocked(scanId, true);
    }

    public synchronized void cancel() {
        clearActiveLocked();
    }

    private void onQuietWindowCompleteLocked(long scanId) {
        if (!isActive(scanId, Phase.QUIET_GUARD)) {
            return;
        }
        currentBoardAddress = FIRST_BOARD_ADDRESS;
        beginQueryLocked(scanId);
    }

    private void beginQueryLocked(long scanId) {
        if (!isActive(scanId)
                || currentBoardAddress < FIRST_BOARD_ADDRESS
                || currentBoardAddress > LAST_BOARD_ADDRESS) {
            return;
        }

        cancelTimerLocked();
        currentQuery = BoardStatusProtocol.query(currentBoardAddress);
        responseDetector = new BoardStatusResponseDetector(currentBoardAddress);
        bufferedPresent = false;
        sentWhileQueueing = false;
        phase = Phase.QUEUEING;

        boolean accepted;
        try {
            accepted = serialActions.send(
                    scanId, Arrays.copyOf(currentQuery, currentQuery.length));
        } catch (RuntimeException exception) {
            if (isActive(scanId)) {
                failLocked(scanId, Failure.SEND);
            }
            return;
        }
        if (!isActive(scanId)) {
            return;
        }
        if (!accepted) {
            failLocked(scanId, Failure.SEND);
            return;
        }

        if (sentWhileQueueing) {
            beginResponseWindowLocked(scanId);
            return;
        }
        phase = Phase.AWAITING_SENT;
        if (!scheduleLocked(scanId, SENT_WATCHDOG_MILLIS,
                () -> onSentWatchdogLocked(scanId))) {
            failLocked(scanId, Failure.SEND);
        }
    }

    private void beginResponseWindowLocked(long scanId) {
        if (!isActive(scanId)) {
            return;
        }
        phase = Phase.WAITING_RESPONSE;
        if (bufferedPresent) {
            completeCurrentBoardLocked(scanId, true);
            return;
        }
        if (!scheduleLocked(scanId, RESPONSE_TIMEOUT_MILLIS,
                () -> onResponseTimeoutLocked(scanId))) {
            failLocked(scanId, Failure.SERIAL);
        }
    }

    private void onSentWatchdogLocked(long scanId) {
        if (isActive(scanId, Phase.AWAITING_SENT)) {
            failLocked(scanId, Failure.SEND);
        }
    }

    private void onResponseTimeoutLocked(long scanId) {
        if (isActive(scanId, Phase.WAITING_RESPONSE)) {
            completeCurrentBoardLocked(scanId, false);
        }
    }

    private void completeCurrentBoardLocked(long scanId, boolean present) {
        if (!isActive(scanId, Phase.WAITING_RESPONSE)) {
            return;
        }
        cancelTimerLocked();
        if (present) {
            onlineZones.add(zoneForAddress(currentBoardAddress));
        }

        currentQuery = null;
        responseDetector = null;
        bufferedPresent = false;
        sentWhileQueueing = false;
        if (currentBoardAddress == LAST_BOARD_ADDRESS) {
            completeLocked(scanId);
            return;
        }

        currentBoardAddress++;
        phase = Phase.GAP;
        if (!scheduleLocked(scanId, BOARD_GAP_MILLIS,
                () -> onBoardGapCompleteLocked(scanId))) {
            failLocked(scanId, Failure.SERIAL);
        }
    }

    private void onBoardGapCompleteLocked(long scanId) {
        if (isActive(scanId, Phase.GAP)) {
            beginQueryLocked(scanId);
        }
    }

    private void completeLocked(long scanId) {
        List<LockerZone> snapshot = Collections.unmodifiableList(
                new ArrayList<>(onlineZones));
        clearActiveLocked();
        listener.onDiscoveryCompleted(scanId, snapshot);
    }

    private void failLocked(long scanId, Failure failure) {
        clearActiveLocked();
        listener.onDiscoveryFailed(scanId, failure);
    }

    private boolean scheduleLocked(long scanId, long delayMillis, Runnable task) {
        cancelTimerLocked();
        final long generation = ++timerGeneration;
        try {
            Cancellable scheduled = scheduler.schedule(() -> {
                synchronized (BoardDiscoveryCoordinator.this) {
                    if (!isActive(scanId) || timerGeneration != generation) {
                        return;
                    }
                    timerTask = null;
                    task.run();
                }
            }, delayMillis);
            if (scheduled == null) {
                return false;
            }
            timerTask = scheduled;
            return true;
        } catch (RuntimeException exception) {
            timerTask = null;
            return false;
        }
    }

    private void cancelTimerLocked() {
        timerGeneration++;
        Cancellable current = timerTask;
        timerTask = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // Generation and reference were invalidated first; cleanup must continue.
            }
        }
    }

    private void clearActiveLocked() {
        cancelTimerLocked();
        active = false;
        activeScanId = 0L;
        phase = Phase.IDLE;
        currentBoardAddress = 0;
        currentQuery = null;
        responseDetector = null;
        bufferedPresent = false;
        sentWhileQueueing = false;
        onlineZones.clear();
    }

    private boolean matchesCurrentQuery(byte[] bytes) {
        return currentQuery != null && bytes != null
                && Arrays.equals(currentQuery, bytes);
    }

    private boolean isActive(long scanId) {
        return active && scanId > 0L && activeScanId == scanId;
    }

    private boolean isActive(long scanId, Phase expectedPhase) {
        return isActive(scanId) && phase == expectedPhase;
    }

    private static LockerZone zoneForAddress(int boardAddress) {
        switch (boardAddress) {
            case 1:
                return LockerZone.A;
            case 2:
                return LockerZone.B;
            case 3:
                return LockerZone.C;
            default:
                throw new IllegalArgumentException("Unsupported board address");
        }
    }

    private long nextScanId() {
        nextScanId++;
        if (nextScanId <= 0L) {
            nextScanId = 1L;
        }
        return nextScanId;
    }
}
