package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.DoorStateResponseDetector;

/** Fail-closed monitor requiring an observed open and two later closed poll responses. */
public final class DoorCloseMonitor {
    public enum Outcome {
        BASELINE_CLOSED,
        READY_FOR_UNLOCK,
        WAITING_FOR_OPEN,
        WAITING_FOR_STABLE_CLOSE,
        REQUEST_IMMEDIATE_POLL,
        STATUS_UNCERTAIN,
        COMPLETE,
        CANCELLED,
        REJECTED
    }

    private final long operationId;
    private final LockerTarget target;
    private boolean baselineClosed;
    private boolean unlockSent;
    private boolean openObserved;
    private int consecutiveClosed;
    private boolean uncertain;
    private boolean cancelled;
    private boolean complete;

    public DoorCloseMonitor(long operationId, LockerTarget target) {
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation ID must be positive");
        }
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        this.operationId = operationId;
        this.target = target;
    }

    public synchronized Outcome observePollState(long expectedOperationId, LockerTarget expectedTarget,
            DoorStateResponseDetector.PhysicalState state) {
        if (!isCurrent(expectedOperationId, expectedTarget)) {
            return Outcome.REJECTED;
        }
        if (complete) {
            return Outcome.COMPLETE;
        }
        if (uncertain) {
            return Outcome.STATUS_UNCERTAIN;
        }
        if (state == null || state == DoorStateResponseDetector.PhysicalState.UNKNOWN) {
            if (unlockSent && openObserved) {
                consecutiveClosed = 0;
            }
            return currentWaitingOutcome();
        }
        if (!baselineClosed) {
            if (state == DoorStateResponseDetector.PhysicalState.CLOSED) {
                baselineClosed = true;
                return Outcome.BASELINE_CLOSED;
            }
            return Outcome.STATUS_UNCERTAIN;
        }
        if (!unlockSent) {
            if (state == DoorStateResponseDetector.PhysicalState.CLOSED) {
                return Outcome.BASELINE_CLOSED;
            }
            baselineClosed = false;
            return Outcome.STATUS_UNCERTAIN;
        }
        if (state == DoorStateResponseDetector.PhysicalState.OPEN) {
            openObserved = true;
            consecutiveClosed = 0;
            return Outcome.WAITING_FOR_STABLE_CLOSE;
        }
        if (!openObserved) {
            return Outcome.WAITING_FOR_OPEN;
        }
        consecutiveClosed++;
        if (consecutiveClosed == 2) {
            complete = true;
            return Outcome.COMPLETE;
        }
        return Outcome.WAITING_FOR_STABLE_CLOSE;
    }

    public synchronized Outcome markUnlockSent(long expectedOperationId, LockerTarget expectedTarget) {
        if (!isCurrent(expectedOperationId, expectedTarget)) {
            return Outcome.REJECTED;
        }
        if (complete) {
            return Outcome.COMPLETE;
        }
        if (uncertain || !baselineClosed) {
            return Outcome.REJECTED;
        }
        unlockSent = true;
        return Outcome.WAITING_FOR_OPEN;
    }

    public synchronized Outcome observeActivePush(long expectedOperationId, LockerTarget expectedTarget) {
        if (!isCurrent(expectedOperationId, expectedTarget) || complete) {
            return Outcome.REJECTED;
        }
        return Outcome.REQUEST_IMMEDIATE_POLL;
    }

    public synchronized Outcome timeout(long expectedOperationId, LockerTarget expectedTarget) {
        return markUncertain(expectedOperationId, expectedTarget);
    }

    public synchronized Outcome disconnect(long expectedOperationId, LockerTarget expectedTarget) {
        return markUncertain(expectedOperationId, expectedTarget);
    }

    public synchronized Outcome parserError(long expectedOperationId, LockerTarget expectedTarget) {
        return markUncertain(expectedOperationId, expectedTarget);
    }

    public synchronized Outcome resume(long expectedOperationId, LockerTarget expectedTarget) {
        if (!isCurrent(expectedOperationId, expectedTarget) || complete) {
            return Outcome.REJECTED;
        }
        uncertain = false;
        return currentWaitingOutcome();
    }

    public synchronized Outcome cancel(long expectedOperationId, LockerTarget expectedTarget) {
        if (!isCurrent(expectedOperationId, expectedTarget) || complete) {
            return Outcome.REJECTED;
        }
        baselineClosed = false;
        unlockSent = false;
        openObserved = false;
        consecutiveClosed = 0;
        uncertain = false;
        cancelled = true;
        return Outcome.CANCELLED;
    }

    public synchronized Outcome reset(long expectedOperationId, LockerTarget expectedTarget) {
        return cancel(expectedOperationId, expectedTarget);
    }

    public synchronized boolean isComplete() {
        return complete;
    }

    public long operationId() {
        return operationId;
    }

    public LockerTarget target() {
        return target;
    }

    private Outcome markUncertain(long expectedOperationId, LockerTarget expectedTarget) {
        if (!isCurrent(expectedOperationId, expectedTarget) || complete) {
            return Outcome.REJECTED;
        }
        consecutiveClosed = 0;
        uncertain = true;
        return Outcome.STATUS_UNCERTAIN;
    }

    private boolean isCurrent(long expectedOperationId, LockerTarget expectedTarget) {
        return !cancelled && expectedOperationId == operationId && sameTarget(target, expectedTarget);
    }

    private Outcome currentWaitingOutcome() {
        if (!baselineClosed) {
            return Outcome.STATUS_UNCERTAIN;
        }
        if (!unlockSent) {
            return Outcome.READY_FOR_UNLOCK;
        }
        return openObserved ? Outcome.WAITING_FOR_STABLE_CLOSE : Outcome.WAITING_FOR_OPEN;
    }

    private static boolean sameTarget(LockerTarget first, LockerTarget second) {
        return first != null && second != null
                && first.zone() == second.zone()
                && first.boardAddress() == second.boardAddress()
                && first.localLock() == second.localLock()
                && first.feedbackPolarity() == second.feedbackPolarity();
    }
}
