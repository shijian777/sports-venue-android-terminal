package com.codex.lockertest.face;

/** Pure state holder for process-wide Baidu authorization. */
public final class FaceLicenseStateMachine {
    public static final long OPERATION_TIMEOUT_MILLIS = 15000L;
    static final int MAX_ACTIVATION_CHARACTERS = 4096;

    public enum State {
        UNKNOWN,
        CHECKING_LOCAL,
        ACTIVATING_ONLINE,
        READY,
        INVALID,
        FAILED
    }

    public enum OperationKind {
        LOCAL_CHECK,
        ONLINE_ACTIVATION
    }

    public static final class StartResult {
        private final long operationId;
        private final boolean shouldStart;

        private StartResult(long operationId, boolean shouldStart) {
            this.operationId = operationId;
            this.shouldStart = shouldStart;
        }

        public long operationId() { return operationId; }
        public boolean shouldStart() { return shouldStart; }
    }

    private State state = State.UNKNOWN;
    private OperationKind activeKind;
    private long activeOperationId;
    private long lastOperationId;
    private boolean commitClaimed;

    public FaceLicenseStateMachine() {
        this(0L);
    }

    FaceLicenseStateMachine(long lastOperationId) {
        if (lastOperationId < 0L) {
            throw new IllegalArgumentException("last operation ID cannot be negative");
        }
        this.lastOperationId = lastOperationId;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized StartResult startLocalCheck() {
        if (isInFlight()) return new StartResult(activeOperationId, false);
        if (state != State.UNKNOWN && state != State.INVALID && state != State.FAILED) {
            return new StartResult(0L, false);
        }
        return start(OperationKind.LOCAL_CHECK, State.CHECKING_LOCAL);
    }

    public synchronized StartResult startOnlineActivation() {
        if (isInFlight()) return new StartResult(activeOperationId, false);
        if (state == State.READY) return new StartResult(0L, false);
        if (state != State.UNKNOWN && state != State.INVALID && state != State.FAILED) {
            return new StartResult(0L, false);
        }
        return start(OperationKind.ONLINE_ACTIVATION, State.ACTIVATING_ONLINE);
    }

    public synchronized boolean completeSuccess(long operationId, OperationKind kind) {
        return finish(operationId, kind, State.READY);
    }

    public synchronized boolean completeInvalid(long operationId, OperationKind kind) {
        return finish(operationId, kind, State.INVALID);
    }

    public synchronized boolean completeFailure(long operationId, OperationKind kind) {
        return finish(operationId, kind, State.FAILED);
    }

    public synchronized boolean timeout(long operationId, OperationKind kind) {
        if (commitClaimed && operationId == activeOperationId && kind == activeKind) {
            return false;
        }
        return finish(operationId, kind, State.FAILED);
    }

    /** Claims the persistence/native-auth phase before a timeout can win. */
    public synchronized boolean tryBeginCommit(long operationId, OperationKind kind) {
        if (commitClaimed || kind != OperationKind.ONLINE_ACTIVATION
                || operationId <= 0L || operationId != activeOperationId
                || kind != activeKind || state != State.ACTIVATING_ONLINE) {
            return false;
        }
        commitClaimed = true;
        return true;
    }

    static boolean isActivationLengthAllowed(int length) {
        return length > 0 && length <= MAX_ACTIVATION_CHARACTERS;
    }

    private StartResult start(OperationKind kind, State startedState) {
        if (lastOperationId == Long.MAX_VALUE) {
            state = State.FAILED;
            activeOperationId = 0L;
            activeKind = null;
            return new StartResult(0L, false);
        }
        lastOperationId++;
        activeOperationId = lastOperationId;
        activeKind = kind;
        commitClaimed = false;
        state = startedState;
        return new StartResult(activeOperationId, true);
    }

    private boolean finish(long operationId, OperationKind kind, State terminal) {
        if (operationId <= 0L || operationId != activeOperationId || kind != activeKind
                || !isExpectedState(kind)) {
            return false;
        }
        state = terminal;
        activeOperationId = 0L;
        activeKind = null;
        commitClaimed = false;
        return true;
    }

    private boolean isInFlight() {
        return state == State.CHECKING_LOCAL || state == State.ACTIVATING_ONLINE;
    }

    private boolean isExpectedState(OperationKind kind) {
        return (kind == OperationKind.LOCAL_CHECK && state == State.CHECKING_LOCAL)
                || (kind == OperationKind.ONLINE_ACTIVATION
                && state == State.ACTIVATING_ONLINE);
    }
}
