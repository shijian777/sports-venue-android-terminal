package com.codex.lockertest.unlock;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.LockerResponseDetector;

import java.util.Arrays;

public final class UnlockCoordinator {
    public enum State {
        VERIFYING,
        CONNECTING,
        QUIETING,
        WAITING_ACK,
        SUCCESS,
        FAILURE
    }

    public interface Listener {
        void onStateChanged(long attemptId, State state, String detail);
    }

    public interface SerialActions {
        void ensureDefaultConnected(long attemptId);

        boolean send(long attemptId, byte[] bytes);
    }

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);
    }

    public interface Cancellable {
        void cancel();
    }

    private enum Phase {
        IDLE,
        CONNECTING,
        QUIETING,
        QUEUEING,
        AWAITING_SENT,
        WAITING_ACK
    }

    private static final long QUIET_GUARD_MILLIS = 300L;
    private static final long SENT_WATCHDOG_MILLIS = 1_000L;
    private static final long ACK_TIMEOUT_MILLIS = 3_000L;
    private static final String OPEN_FAILURE = "设备连接失败，请联系管理员。";
    private static final String SEND_FAILURE = "开柜指令发送失败，请再次尝试。";
    private static final String TIMEOUT_FAILURE = "设备无响应，请再次尝试。";

    private final SerialActions serialActions;
    private final Scheduler scheduler;
    private final Listener listener;

    private long nextAttemptId;
    private long activeAttemptId;
    private long quietGeneration;
    private boolean active;
    private boolean sentObservedDuringSend;
    private State state;
    private Phase phase = Phase.IDLE;
    private LockerTarget activeTarget;
    private AuthorizedUnlockRequest activeAuthorizedRequest;
    private ExactFrameResponseDetector exactResponseDetector;
    private LockerResponseDetector.Result bufferedResult = LockerResponseDetector.Result.NONE;
    private byte[] expectedCommand;
    private Cancellable quietTask;
    private Cancellable sentWatchdogTask;
    private Cancellable ackTimeoutTask;

    public UnlockCoordinator(
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

    public synchronized long startAuthorized(AuthorizedUnlockRequest request) {
        if (active) {
            return 0L;
        }
        if (request == null) {
            reportRejectedStartLocked("开柜授权无效，请再次验证。");
            return 0L;
        }

        final AuthorizedUnlockRequest safeRequest;
        try {
            safeRequest = new AuthorizedUnlockRequest(
                    request.operationId(),
                    request.target(),
                    request.unlockCommand(),
                    request.expectedSuccessFrame(),
                    request.expectedFailureFrame());
        } catch (RuntimeException exception) {
            reportRejectedStartLocked("开柜授权无效，请再次验证。");
            return 0L;
        }

        long attemptId = nextAttemptId();
        activeAttemptId = attemptId;
        activeTarget = safeRequest.target();
        activeAuthorizedRequest = safeRequest;
        active = true;
        phase = Phase.CONNECTING;
        state = State.VERIFYING;
        listener.onStateChanged(attemptId, State.VERIFYING, "正在验证开柜授权");
        if (!isActiveAttempt(attemptId, Phase.CONNECTING)) {
            return attemptId;
        }

        state = State.CONNECTING;
        listener.onStateChanged(attemptId, State.CONNECTING, "正在连接设备");
        if (!isActiveAttempt(attemptId, Phase.CONNECTING)) {
            return attemptId;
        }

        try {
            serialActions.ensureDefaultConnected(attemptId);
        } catch (RuntimeException exception) {
            if (isActiveAttempt(attemptId, Phase.CONNECTING)) {
                failLocked(attemptId, OPEN_FAILURE);
            }
        }
        return attemptId;
    }

    public synchronized void onSerialConnected(long attemptId) {
        if (!isActiveAttempt(attemptId, Phase.CONNECTING)) {
            return;
        }

        phase = Phase.QUIETING;
        state = State.QUIETING;
        try {
            restartQuietGuardLocked(attemptId);
        } catch (RuntimeException exception) {
            if (isActiveAttempt(attemptId, Phase.QUIETING)) {
                failLocked(attemptId, OPEN_FAILURE);
            }
            return;
        }
        listener.onStateChanged(attemptId, State.QUIETING, "正在准备设备");
    }

    public synchronized void onSerialOpenFailed(long attemptId, String detail) {
        if (isActiveAttempt(attemptId)) {
            failLocked(attemptId, OPEN_FAILURE);
        }
    }

    public synchronized void onSerialFailure(long attemptId, String detail) {
        if (isActiveAttempt(attemptId)) {
            failLocked(attemptId, OPEN_FAILURE);
        }
    }

    public synchronized void onSerialSendFailed(
            long attemptId,
            byte[] failedPayload,
            String diagnosticDetail) {
        if (!isActiveAttempt(attemptId)
                || !isQueuedOrSentPhase()
                || expectedCommand == null
                || failedPayload == null
                || !Arrays.equals(expectedCommand, failedPayload)) {
            return;
        }
        failLocked(attemptId, SEND_FAILURE);
    }

    /**
     * Compatibility bridge for the pre-payload-correlated gateway callback.
     */
    @Deprecated
    public synchronized void onSerialSendFailed(long attemptId) {
        if (!isActiveAttempt(attemptId) || expectedCommand == null) {
            return;
        }
        onSerialSendFailed(
                attemptId,
                Arrays.copyOf(expectedCommand, expectedCommand.length),
                null);
    }

    public synchronized void onSerialSent(long attemptId, byte[] bytes) {
        if (!isActiveAttempt(attemptId)
                || expectedCommand == null
                || bytes == null
                || !Arrays.equals(expectedCommand, bytes)) {
            return;
        }

        if (phase == Phase.QUEUEING) {
            sentObservedDuringSend = true;
            return;
        }
        if (phase == Phase.AWAITING_SENT) {
            enterAckWindowLocked(attemptId);
        }
    }

    public synchronized void onSerialBytes(long attemptId, byte[] bytes) {
        if (!isActiveAttempt(attemptId) || bytes == null || bytes.length == 0) {
            return;
        }

        if (phase == Phase.QUIETING) {
            try {
                restartQuietGuardLocked(attemptId);
            } catch (RuntimeException exception) {
                if (isActiveAttempt(attemptId, Phase.QUIETING)) {
                    failLocked(attemptId, OPEN_FAILURE);
                }
            }
            return;
        }

        if (phase != Phase.QUEUEING
                && phase != Phase.AWAITING_SENT
                && phase != Phase.WAITING_ACK) {
            return;
        }
        if (exactResponseDetector == null) {
            return;
        }
        if (bufferedResult != LockerResponseDetector.Result.NONE) {
            return;
        }

        LockerResponseDetector.Result result = exactResponseDetector.append(bytes, bytes.length);
        if (result == LockerResponseDetector.Result.NONE) {
            return;
        }
        if (phase == Phase.WAITING_ACK) {
            commitResultLocked(attemptId, result);
        } else {
            bufferedResult = result;
        }
    }

    public synchronized void cancel() {
        clearActiveAttemptLocked();
    }

    private synchronized void onQuietElapsed(long attemptId, long generation) {
        if (!isActiveAttempt(attemptId, Phase.QUIETING)
                || generation != quietGeneration) {
            return;
        }
        quietTask = null;

        exactResponseDetector = new ExactFrameResponseDetector(
                activeAuthorizedRequest.expectedSuccessFrame(),
                activeAuthorizedRequest.expectedFailureFrame());
        byte[] command = activeAuthorizedRequest.unlockCommand();
        expectedCommand = Arrays.copyOf(command, command.length);
        bufferedResult = LockerResponseDetector.Result.NONE;
        sentObservedDuringSend = false;
        phase = Phase.QUEUEING;

        boolean accepted;
        try {
            accepted = serialActions.send(
                    attemptId,
                    Arrays.copyOf(expectedCommand, expectedCommand.length));
        } catch (RuntimeException exception) {
            if (isActiveAttempt(attemptId)) {
                failLocked(attemptId, SEND_FAILURE);
            }
            return;
        }

        if (!isActiveAttempt(attemptId)) {
            return;
        }
        if (!accepted) {
            failLocked(attemptId, SEND_FAILURE);
            return;
        }

        phase = Phase.AWAITING_SENT;
        if (sentObservedDuringSend) {
            enterAckWindowLocked(attemptId);
            return;
        }
        try {
            sentWatchdogTask = scheduler.schedule(
                    () -> onSentWatchdog(attemptId), SENT_WATCHDOG_MILLIS);
            if (sentWatchdogTask == null) {
                throw new IllegalStateException("Scheduler returned no sent watchdog");
            }
        } catch (RuntimeException exception) {
            if (isActiveAttempt(attemptId, Phase.AWAITING_SENT)) {
                failLocked(attemptId, SEND_FAILURE);
            }
            return;
        }
    }

    private synchronized void onSentWatchdog(long attemptId) {
        if (isActiveAttempt(attemptId, Phase.AWAITING_SENT)) {
            failLocked(attemptId, SEND_FAILURE);
        }
    }

    private synchronized void onAckTimeout(long attemptId) {
        if (isActiveAttempt(attemptId, Phase.WAITING_ACK)) {
            failLocked(attemptId, TIMEOUT_FAILURE);
        }
    }

    private void restartQuietGuardLocked(long attemptId) {
        cancelTaskLocked(quietTask);
        quietTask = null;
        long generation = ++quietGeneration;
        quietTask = scheduler.schedule(
                () -> onQuietElapsed(attemptId, generation), QUIET_GUARD_MILLIS);
        if (quietTask == null) {
            throw new IllegalStateException("Scheduler returned no quiet guard");
        }
    }

    private void enterAckWindowLocked(long attemptId) {
        if (!isActiveAttempt(attemptId, Phase.AWAITING_SENT)) {
            return;
        }

        cancelTaskLocked(sentWatchdogTask);
        sentWatchdogTask = null;
        sentObservedDuringSend = false;
        phase = Phase.WAITING_ACK;
        state = State.WAITING_ACK;

        LockerResponseDetector.Result readyResult = bufferedResult;
        bufferedResult = LockerResponseDetector.Result.NONE;
        if (readyResult == LockerResponseDetector.Result.NONE) {
            try {
                ackTimeoutTask = scheduler.schedule(
                        () -> onAckTimeout(attemptId), ACK_TIMEOUT_MILLIS);
                if (ackTimeoutTask == null) {
                    throw new IllegalStateException("Scheduler returned no ACK timeout");
                }
            } catch (RuntimeException exception) {
                if (isActiveAttempt(attemptId, Phase.WAITING_ACK)) {
                    failLocked(attemptId, SEND_FAILURE);
                }
                return;
            }
        }

        listener.onStateChanged(attemptId, State.WAITING_ACK, "正在开启柜门，请稍候");
        if (readyResult != LockerResponseDetector.Result.NONE
                && isActiveAttempt(attemptId, Phase.WAITING_ACK)) {
            commitResultLocked(attemptId, readyResult);
        }
    }

    private void commitResultLocked(
            long attemptId,
            LockerResponseDetector.Result result) {
        if (result == LockerResponseDetector.Result.SUCCESS) {
            succeedLocked(attemptId);
        } else if (result == LockerResponseDetector.Result.FAILURE) {
            failSelectedLockerLocked(attemptId);
        }
    }

    private void reportRejectedStartLocked(String detail) {
        state = State.FAILURE;
        listener.onStateChanged(0L, State.FAILURE, detail);
    }

    private void succeedLocked(long attemptId) {
        String label = activeTarget.customerLabel();
        finishLocked(
                attemptId,
                State.SUCCESS,
                label + "号柜门已打开，请存放物品后关闭柜门。");
    }

    private void failSelectedLockerLocked(long attemptId) {
        String label = activeTarget.customerLabel();
        failLocked(attemptId, label + "号柜门开启失败，请再次尝试。");
    }

    private void failLocked(long attemptId, String detail) {
        finishLocked(attemptId, State.FAILURE, detail);
    }

    private void finishLocked(long attemptId, State terminalState, String detail) {
        clearActiveAttemptLocked();
        state = terminalState;
        listener.onStateChanged(attemptId, terminalState, detail);
    }

    private void clearActiveAttemptLocked() {
        active = false;
        activeAttemptId = 0L;
        activeTarget = null;
        activeAuthorizedRequest = null;
        phase = Phase.IDLE;
        quietGeneration++;
        sentObservedDuringSend = false;
        expectedCommand = null;
        bufferedResult = LockerResponseDetector.Result.NONE;
        if (exactResponseDetector != null) {
            exactResponseDetector.reset();
            exactResponseDetector = null;
        }
        cancelTaskLocked(quietTask);
        cancelTaskLocked(sentWatchdogTask);
        cancelTaskLocked(ackTimeoutTask);
        quietTask = null;
        sentWatchdogTask = null;
        ackTimeoutTask = null;
    }

    private void cancelTaskLocked(Cancellable task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // Attempt state is authoritative even if a scheduler cannot remove its callback.
        }
    }

    private boolean isQueuedOrSentPhase() {
        return phase == Phase.QUEUEING
                || phase == Phase.AWAITING_SENT
                || phase == Phase.WAITING_ACK;
    }

    private boolean isActiveAttempt(long attemptId) {
        return active && attemptId > 0L && activeAttemptId == attemptId;
    }

    private boolean isActiveAttempt(long attemptId, Phase expectedPhase) {
        return isActiveAttempt(attemptId) && phase == expectedPhase;
    }

    private long nextAttemptId() {
        nextAttemptId++;
        if (nextAttemptId <= 0L) {
            nextAttemptId = 1L;
        }
        return nextAttemptId;
    }

    private static final class ExactFrameResponseDetector {
        private final PatternMatcher successMatcher;
        private final PatternMatcher failureMatcher;

        private ExactFrameResponseDetector(byte[] successFrame, byte[] failureFrame) {
            successMatcher = new PatternMatcher(successFrame);
            failureMatcher = new PatternMatcher(failureFrame);
        }

        private LockerResponseDetector.Result append(byte[] bytes, int length) {
            for (int index = 0; index < length; index++) {
                byte value = bytes[index];
                if (successMatcher.append(value)) {
                    return LockerResponseDetector.Result.SUCCESS;
                }
                if (failureMatcher.append(value)) {
                    return LockerResponseDetector.Result.FAILURE;
                }
            }
            return LockerResponseDetector.Result.NONE;
        }

        private void reset() {
            successMatcher.reset();
            failureMatcher.reset();
        }
    }

    private static final class PatternMatcher {
        private final byte[] pattern;
        private int matched;

        private PatternMatcher(byte[] pattern) {
            this.pattern = pattern.clone();
        }

        private boolean append(byte value) {
            if (value == pattern[matched]) {
                matched++;
                if (matched == pattern.length) {
                    matched = 0;
                    return true;
                }
            } else {
                matched = value == pattern[0] ? 1 : 0;
            }
            return false;
        }

        private void reset() {
            matched = 0;
        }
    }
}
