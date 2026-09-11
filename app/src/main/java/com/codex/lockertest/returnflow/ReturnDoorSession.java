package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.DoorStateResponseDetector;

/**
 * Correlated, fail-closed physical-door session for one authorized return operation.
 * It owns no scheduler or transport; callers provide one exact poll at a time.
 */
public final class ReturnDoorSession {
    public enum Event {
        NONE,
        BASELINE_CLOSED,
        WAITING_FOR_OPEN,
        WAITING_FOR_STABLE_CLOSE,
        STABLE_CLOSE,
        READY_TO_COMMIT,
        REQUEST_IMMEDIATE_POLL,
        STATUS_UNCERTAIN,
        UNLOCK_FAILED,
        REJECTED
    }

    private final long generation;
    private final long operationId;
    private final LockerTarget target;
    private final DoorStateResponseDetector detector;
    private final DoorCloseMonitor monitor;

    private long nextPollId;
    private long activePollId;
    private boolean activePollSent;
    private boolean statusUncertain;
    private boolean unlockSent;
    private boolean unlockAckAccepted;
    private boolean unlockAckTerminal;
    private boolean userAcknowledgedClose;
    private long acknowledgementPollBoundary;
    private boolean stableCloseObserved;
    private boolean readyToCommit;
    private boolean cancelled;

    public ReturnDoorSession(long generation, long operationId, LockerTarget target) {
        if (generation <= 0L || operationId <= 0L || target == null) {
            throw new IllegalArgumentException("Return door session identity is required");
        }
        this.generation = generation;
        this.operationId = operationId;
        this.target = target;
        detector = new DoorStateResponseDetector(target);
        monitor = new DoorCloseMonitor(operationId, target);
    }

    public synchronized long beginPoll(long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || readyToCommit
                || activePollId != 0L
                || nextPollId == Long.MAX_VALUE) {
            return 0L;
        }
        nextPollId++;
        activePollId = nextPollId;
        activePollSent = false;
        return activePollId;
    }

    public synchronized boolean markPollSent(long expectedGeneration,
            long expectedOperationId, long pollId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || pollId <= 0L
                || activePollId != pollId
                || activePollSent) {
            return false;
        }
        activePollSent = true;
        return true;
    }

    public synchronized Event onBytes(long expectedGeneration,
            long expectedOperationId, byte[] bytes) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || bytes == null
                || bytes.length == 0) {
            return Event.REJECTED;
        }

        DoorStateResponseDetector.AppendResult result;
        try {
            result = detector.append(bytes, bytes.length);
        } catch (RuntimeException | LinkageError ignored) {
            return markUncertainLocked();
        }

        boolean immediatePoll = !result.signals().isEmpty();
        for (DoorStateResponseDetector.Event event : result.events()) {
            if (event.source() == DoorStateResponseDetector.Source.ACTIVE_PUSH) {
                continue;
            }
            if (activePollId == 0L || !activePollSent) {
                continue;
            }
            long responsePollId = activePollId;
            activePollId = 0L;
            activePollSent = false;
            if (statusUncertain) {
                DoorCloseMonitor.Outcome resumed = monitor.resume(operationId, target);
                if (resumed == DoorCloseMonitor.Outcome.REJECTED) {
                    return Event.REJECTED;
                }
                statusUncertain = false;
            }
            DoorCloseMonitor.Outcome outcome = monitor.observePollState(
                    operationId, target, event.physicalState());
            Event mapped = mapOutcome(outcome);
            if (outcome == DoorCloseMonitor.Outcome.COMPLETE) {
                stableCloseObserved = true;
                if (unlockAckAccepted
                        && userAcknowledgedClose
                        && responsePollId > acknowledgementPollBoundary
                        && event.physicalState()
                        == DoorStateResponseDetector.PhysicalState.CLOSED) {
                    readyToCommit = true;
                    return Event.READY_TO_COMMIT;
                }
                return Event.STABLE_CLOSE;
            }
            return mapped;
        }
        return immediatePoll ? Event.REQUEST_IMMEDIATE_POLL : Event.NONE;
    }

    public synchronized Event markUnlockSent(
            long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId) || unlockSent) {
            return Event.REJECTED;
        }
        DoorCloseMonitor.Outcome outcome = monitor.markUnlockSent(operationId, target);
        if (outcome != DoorCloseMonitor.Outcome.WAITING_FOR_OPEN) {
            return Event.REJECTED;
        }
        unlockSent = true;
        return Event.WAITING_FOR_OPEN;
    }

    public synchronized Event markUnlockAck(long expectedGeneration,
            long expectedOperationId, boolean success) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || !unlockSent
                || unlockAckTerminal) {
            return Event.REJECTED;
        }
        unlockAckTerminal = true;
        unlockAckAccepted = success;
        if (!success) {
            return Event.UNLOCK_FAILED;
        }
        return Event.NONE;
    }

    /** The customer button only requests a fresh exact poll; it never completes by itself. */
    public synchronized Event acknowledgeDoorClosed(
            long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || !unlockSent
                || !stableCloseObserved) {
            return Event.REJECTED;
        }
        userAcknowledgedClose = true;
        acknowledgementPollBoundary = nextPollId;
        return Event.REQUEST_IMMEDIATE_POLL;
    }

    public synchronized Event onPollTimeout(
            long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)
                || activePollId == 0L) {
            return Event.REJECTED;
        }
        activePollId = 0L;
        activePollSent = false;
        detector.reset();
        return markUncertainLocked();
    }

    public synchronized Event onDisconnected(
            long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)) {
            return Event.REJECTED;
        }
        activePollId = 0L;
        activePollSent = false;
        detector.reset();
        return markUncertainLocked();
    }

    public synchronized boolean cancel(
            long expectedGeneration, long expectedOperationId) {
        if (!isCurrent(expectedGeneration, expectedOperationId)) {
            return false;
        }
        cancelled = true;
        activePollId = 0L;
        activePollSent = false;
        detector.reset();
        monitor.cancel(operationId, target);
        return true;
    }

    public synchronized boolean hasPollInFlight() {
        return activePollId != 0L;
    }

    public synchronized long activePollId() {
        return activePollId;
    }

    public synchronized boolean isUnlockSent() {
        return unlockSent;
    }

    public synchronized boolean isUnlockAckAccepted() {
        return unlockAckAccepted;
    }

    public synchronized boolean isUserAcknowledgedClose() {
        return userAcknowledgedClose;
    }

    public synchronized boolean isStableCloseObserved() {
        return stableCloseObserved;
    }

    public synchronized boolean isReadyToCommit() {
        return readyToCommit;
    }

    public long generation() {
        return generation;
    }

    public long operationId() {
        return operationId;
    }

    public LockerTarget target() {
        return target;
    }

    private Event markUncertainLocked() {
        DoorCloseMonitor.Outcome outcome = monitor.timeout(operationId, target);
        if (outcome == DoorCloseMonitor.Outcome.REJECTED && !monitor.isComplete()) {
            return Event.REJECTED;
        }
        statusUncertain = !monitor.isComplete();
        return Event.STATUS_UNCERTAIN;
    }

    private boolean isCurrent(long expectedGeneration, long expectedOperationId) {
        return !cancelled
                && expectedGeneration == generation
                && expectedOperationId == operationId;
    }

    private static Event mapOutcome(DoorCloseMonitor.Outcome outcome) {
        if (outcome == DoorCloseMonitor.Outcome.BASELINE_CLOSED
                || outcome == DoorCloseMonitor.Outcome.READY_FOR_UNLOCK) {
            return Event.BASELINE_CLOSED;
        }
        if (outcome == DoorCloseMonitor.Outcome.WAITING_FOR_OPEN) {
            return Event.WAITING_FOR_OPEN;
        }
        if (outcome == DoorCloseMonitor.Outcome.WAITING_FOR_STABLE_CLOSE) {
            return Event.WAITING_FOR_STABLE_CLOSE;
        }
        if (outcome == DoorCloseMonitor.Outcome.STATUS_UNCERTAIN) {
            return Event.STATUS_UNCERTAIN;
        }
        return Event.REJECTED;
    }
}
