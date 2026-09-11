package com.codex.lockertest.palm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

public final class PalmTestController {
    public enum State {
        IDLE, CONNECTING, READY, ENROLLING, ENROLLED,
        VERIFYING, MATCHED, NOT_MATCHED, FAILED, CLOSED
    }

    public interface Listener { void onState(State state, int code); }

    public interface Scheduler { Runnable schedule(Runnable task, long delayMillis); }

    public interface Driver {
        interface Events {
            void onConnected();
            void onFeature(byte[] ownedFeature);
            void onFailure(int code);
            void onDisconnected();
        }

        void connect(Events events);
        void enroll(Events events);
        void capture(Events events);
        int verify(byte[] enrolled, byte[] presented);
        void stop();
        void close();
    }

    public static final int ERROR_TIMEOUT = -10_001;
    public static final int ERROR_DRIVER = -10_002;
    public static final int ERROR_SCHEDULER = -10_003;
    public static final int ERROR_INVALID_FEATURE = -10_004;
    public static final int ERROR_EXECUTOR = -10_005;
    public static final int ERROR_DISCONNECTED = -10_006;

    private static final long CONNECT_TIMEOUT_MILLIS = 10_000L;
    private static final long ENROLL_TIMEOUT_MILLIS = 30_000L;
    private static final long VERIFY_TIMEOUT_MILLIS = 10_000L;
    private static final int MAX_FEATURE_BYTES = 65_536;
    private static final int MATCH_THRESHOLD = 70;

    private enum Operation { CONNECT, ENROLL, VERIFY }

    private static final class FeatureBox {
        byte[] bytes;

        FeatureBox(byte[] bytes) { this.bytes = bytes; }
    }

    private final Driver driver;
    private final Executor worker;
    private final Scheduler scheduler;
    private final Listener listener;
    private final List<FeatureBox> pendingFeatures = new ArrayList<FeatureBox>();

    private State state = State.IDLE;
    private long connectionGeneration;
    private long operationGeneration;
    private Runnable timeoutCancellation;
    private byte[] enrolledFeature;
    private boolean featureClaimed;

    public PalmTestController(Driver driver, Executor worker,
            Scheduler scheduler, Listener listener) {
        if (driver == null || worker == null || scheduler == null || listener == null) {
            throw new IllegalArgumentException("controller dependencies cannot be null");
        }
        this.driver = driver;
        this.worker = worker;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public void connect() {
        beginConnect();
    }

    public void enroll() {
        beginEnroll();
    }

    public void verify() {
        beginVerify();
    }

    public void close() {
        Runnable cancellation;
        byte[] enrolled;
        List<byte[]> pending;
        synchronized (this) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            connectionGeneration = nextGeneration(connectionGeneration);
            operationGeneration = nextGeneration(operationGeneration);
            featureClaimed = false;
            cancellation = takeTimeoutLocked();
            enrolled = enrolledFeature;
            enrolledFeature = null;
            pending = takePendingFeaturesLocked();
        }
        zero(enrolled);
        zeroAll(pending);
        safeCancel(cancellation);
        notifyListener(State.CLOSED, 0);
        enqueueCleanup(new Runnable() {
            @Override public void run() {
                tryStop();
                safeClose();
            }
        });
    }

    public State state() {
        synchronized (this) {
            return state;
        }
    }

    private void beginConnect() {
        final long connection;
        final long operation;
        final boolean reconnect;
        Runnable oldCancellation;
        byte[] oldFeature;
        List<byte[]> pending;
        synchronized (this) {
            if (state != State.IDLE && state != State.FAILED) return;
            reconnect = state == State.FAILED;
            connectionGeneration = nextGeneration(connectionGeneration);
            operationGeneration = nextGeneration(operationGeneration);
            connection = connectionGeneration;
            operation = operationGeneration;
            state = State.CONNECTING;
            featureClaimed = false;
            oldCancellation = takeTimeoutLocked();
            oldFeature = enrolledFeature;
            enrolledFeature = null;
            pending = takePendingFeaturesLocked();
        }
        safeCancel(oldCancellation);
        zero(oldFeature);
        zeroAll(pending);
        notifyListener(State.CONNECTING, 0);
        if (!armTimeout(connection, operation, State.CONNECTING,
                CONNECT_TIMEOUT_MILLIS)) return;
        enqueueWorker(new Runnable() {
            @Override public void run() {
                openConnection(connection, operation, reconnect);
            }
        });
    }

    private void openConnection(long connection, long operation, boolean reconnect) {
        if (!isCurrent(connection, operation, State.CONNECTING)) return;
        if (reconnect) {
            if (!safeClose()) {
                failCurrent(connection, operation, State.CONNECTING, ERROR_DRIVER);
                return;
            }
            if (!isCurrent(connection, operation, State.CONNECTING)) return;
        }
        try {
            driver.connect(new SessionEvents(connection, operation, Operation.CONNECT));
        } catch (RuntimeException ignored) {
            failCurrent(connection, operation, State.CONNECTING, ERROR_DRIVER);
        } catch (LinkageError ignored) {
            failCurrent(connection, operation, State.CONNECTING, ERROR_DRIVER);
        }
    }

    private void beginEnroll() {
        final long connection;
        final long operation;
        Runnable oldCancellation;
        byte[] oldFeature;
        synchronized (this) {
            if (state != State.READY && state != State.ENROLLED
                    && state != State.MATCHED && state != State.NOT_MATCHED) return;
            connection = connectionGeneration;
            operationGeneration = nextGeneration(operationGeneration);
            operation = operationGeneration;
            state = State.ENROLLING;
            featureClaimed = false;
            oldCancellation = takeTimeoutLocked();
            oldFeature = enrolledFeature;
            enrolledFeature = null;
        }
        safeCancel(oldCancellation);
        zero(oldFeature);
        notifyListener(State.ENROLLING, 0);
        if (!armTimeout(connection, operation, State.ENROLLING,
                ENROLL_TIMEOUT_MILLIS)) return;
        enqueueWorker(new Runnable() {
            @Override public void run() { startEnroll(connection, operation); }
        });
    }

    private void startEnroll(long connection, long operation) {
        if (!isCurrent(connection, operation, State.ENROLLING)) return;
        try {
            driver.enroll(new SessionEvents(connection, operation, Operation.ENROLL));
        } catch (RuntimeException ignored) {
            failCurrent(connection, operation, State.ENROLLING, ERROR_DRIVER);
        } catch (LinkageError ignored) {
            failCurrent(connection, operation, State.ENROLLING, ERROR_DRIVER);
        }
    }

    private void beginVerify() {
        final long connection;
        final long operation;
        Runnable oldCancellation;
        synchronized (this) {
            if ((state != State.ENROLLED && state != State.MATCHED
                    && state != State.NOT_MATCHED) || enrolledFeature == null) return;
            connection = connectionGeneration;
            operationGeneration = nextGeneration(operationGeneration);
            operation = operationGeneration;
            state = State.VERIFYING;
            featureClaimed = false;
            oldCancellation = takeTimeoutLocked();
        }
        safeCancel(oldCancellation);
        notifyListener(State.VERIFYING, 0);
        if (!armTimeout(connection, operation, State.VERIFYING,
                VERIFY_TIMEOUT_MILLIS)) return;
        enqueueWorker(new Runnable() {
            @Override public void run() { startVerify(connection, operation); }
        });
    }

    private void startVerify(long connection, long operation) {
        if (!isCurrent(connection, operation, State.VERIFYING)) return;
        try {
            driver.capture(new SessionEvents(connection, operation, Operation.VERIFY));
        } catch (RuntimeException ignored) {
            failCurrent(connection, operation, State.VERIFYING, ERROR_DRIVER);
        } catch (LinkageError ignored) {
            failCurrent(connection, operation, State.VERIFYING, ERROR_DRIVER);
        }
    }

    private final class SessionEvents implements Driver.Events {
        private final long connection;
        private final long operation;
        private final Operation kind;

        SessionEvents(long connection, long operation, Operation kind) {
            this.connection = connection;
            this.operation = operation;
            this.kind = kind;
        }

        @Override public void onConnected() {
            if (kind != Operation.CONNECT) return;
            enqueueCallback(new Runnable() {
                @Override public void run() { handleConnected(connection, operation); }
            });
        }

        @Override public void onFeature(byte[] ownedFeature) {
            acceptOwnedFeature(connection, operation, kind, ownedFeature);
        }

        @Override public void onFailure(final int code) {
            failCurrentWithQueuedStop(connection, operation, expectedState(kind), code);
        }

        @Override public void onDisconnected() {
            handleDisconnected(connection);
        }
    }

    private void handleConnected(long connection, long operation) {
        Runnable cancellation;
        synchronized (this) {
            if (!isCurrentLocked(connection, operation, State.CONNECTING)) return;
            state = State.READY;
            operationGeneration = nextGeneration(operationGeneration);
            cancellation = takeTimeoutLocked();
        }
        safeCancel(cancellation);
        notifyListener(State.READY, 0);
    }

    private void acceptOwnedFeature(final long connection, final long operation,
            final Operation kind, byte[] ownedFeature) {
        boolean claimed;
        synchronized (this) {
            claimed = kind != Operation.CONNECT
                    && isCurrentLocked(connection, operation, expectedState(kind))
                    && !featureClaimed;
            if (claimed) featureClaimed = true;
        }
        if (!claimed) {
            zero(ownedFeature);
            return;
        }

        byte[] copied = null;
        boolean valid = ownedFeature != null && ownedFeature.length >= 1
                && ownedFeature.length <= MAX_FEATURE_BYTES;
        try {
            if (valid) copied = Arrays.copyOf(ownedFeature, ownedFeature.length);
        } finally {
            zero(ownedFeature);
        }

        if (!valid) {
            enqueueCallback(new Runnable() {
                @Override public void run() {
                    failCurrent(connection, operation, expectedState(kind),
                            ERROR_INVALID_FEATURE);
                }
            });
            return;
        }

        final FeatureBox box = new FeatureBox(copied);
        boolean accepted;
        synchronized (this) {
            accepted = isCurrentLocked(connection, operation, expectedState(kind));
            if (accepted) pendingFeatures.add(box);
        }
        if (!accepted) {
            zero(copied);
            return;
        }
        boolean queued = enqueueCallback(new Runnable() {
            @Override public void run() { handleFeature(connection, operation, kind, box); }
        });
        if (!queued) {
            byte[] abandoned;
            synchronized (this) {
                pendingFeatures.remove(box);
                abandoned = box.bytes;
                box.bytes = null;
            }
            zero(abandoned);
        }
    }

    private void handleFeature(long connection, long operation,
            Operation kind, FeatureBox box) {
        byte[] feature;
        boolean current;
        synchronized (this) {
            feature = box.bytes;
            current = feature != null
                    && isCurrentLocked(connection, operation, expectedState(kind));
            if (!current) {
                pendingFeatures.remove(box);
                box.bytes = null;
            }
        }
        if (!current) {
            zero(feature);
            return;
        }
        if (kind == Operation.ENROLL) {
            finishEnrollment(connection, operation, box, feature);
            return;
        }
        if (kind == Operation.VERIFY) {
            verifyFeature(connection, operation, box, feature);
        }
    }

    private void finishEnrollment(long connection, long operation,
            FeatureBox box, byte[] feature) {
        if (!tryStop()) {
            zero(releaseFeature(box));
            failCurrent(connection, operation, State.ENROLLING, ERROR_DRIVER, false);
            return;
        }
        Runnable cancellation;
        boolean completed;
        synchronized (this) {
            completed = isCurrentLocked(connection, operation, State.ENROLLING)
                    && box.bytes == feature;
            if (completed) {
                pendingFeatures.remove(box);
                box.bytes = null;
                enrolledFeature = feature;
                state = State.ENROLLED;
                operationGeneration = nextGeneration(operationGeneration);
                cancellation = takeTimeoutLocked();
            } else {
                cancellation = null;
            }
        }
        if (!completed) {
            zero(releaseFeature(box));
            return;
        }
        safeCancel(cancellation);
        notifyListener(State.ENROLLED, 0);
    }

    private void verifyFeature(long connection, long operation,
            FeatureBox presentedBox, byte[] presented) {
        byte[] enrolled;
        FeatureBox enrolledBox;
        synchronized (this) {
            if (!isCurrentLocked(connection, operation, State.VERIFYING)
                    || enrolledFeature == null) {
                enrolled = null;
                enrolledBox = null;
            } else {
                enrolled = Arrays.copyOf(enrolledFeature, enrolledFeature.length);
                enrolledBox = new FeatureBox(enrolled);
                pendingFeatures.add(enrolledBox);
            }
        }
        if (enrolled == null) {
            zero(releaseFeature(presentedBox));
            return;
        }
        int score;
        try {
            score = driver.verify(enrolled, presented);
        } catch (RuntimeException ignored) {
            score = ERROR_DRIVER;
        } catch (LinkageError ignored) {
            score = ERROR_DRIVER;
        } finally {
            zero(releaseFeature(enrolledBox));
        }
        if (score <= 0) {
            zero(releaseFeature(presentedBox));
            failCurrent(connection, operation, State.VERIFYING, score);
            return;
        }
        if (!isCurrent(connection, operation, State.VERIFYING)) {
            zero(releaseFeature(presentedBox));
            return;
        }
        if (!tryStop()) {
            zero(releaseFeature(presentedBox));
            failCurrent(connection, operation, State.VERIFYING, ERROR_DRIVER, false);
            return;
        }
        zero(releaseFeature(presentedBox));
        State terminal = score > MATCH_THRESHOLD ? State.MATCHED : State.NOT_MATCHED;
        completeCurrent(connection, operation, State.VERIFYING, terminal, score, false);
    }

    private boolean armTimeout(final long connection, final long operation,
            final State expected, long delayMillis) {
        Runnable cancellation;
        try {
            cancellation = scheduler.schedule(new Runnable() {
                @Override public void run() {
                    failCurrentWithQueuedStop(connection, operation, expected,
                            ERROR_TIMEOUT);
                }
            }, delayMillis);
        } catch (RuntimeException ignored) {
            failCurrentWithQueuedStop(connection, operation, expected, ERROR_SCHEDULER);
            return false;
        }
        if (cancellation == null) {
            failCurrentWithQueuedStop(connection, operation, expected, ERROR_SCHEDULER);
            return false;
        }
        boolean installed;
        synchronized (this) {
            installed = isCurrentLocked(connection, operation, expected)
                    && timeoutCancellation == null;
            if (installed) timeoutCancellation = cancellation;
        }
        if (!installed) safeCancel(cancellation);
        return installed;
    }

    private void handleDisconnected(long connection) {
        Runnable cancellation;
        byte[] enrolled;
        List<byte[]> pending;
        long failedOperation;
        synchronized (this) {
            if (connectionGeneration != connection || state == State.IDLE
                    || state == State.FAILED || state == State.CLOSED) return;
            state = State.FAILED;
            operationGeneration = nextGeneration(operationGeneration);
            failedOperation = operationGeneration;
            cancellation = takeTimeoutLocked();
            enrolled = enrolledFeature;
            enrolledFeature = null;
            pending = takePendingFeaturesLocked();
        }
        safeCancel(cancellation);
        zero(enrolled);
        zeroAll(pending);
        notifyListener(State.FAILED, ERROR_DISCONNECTED);
        enqueueFailedCleanup(connection, failedOperation);
    }

    private void failCurrent(long connection, long operation,
            State expected, int code) {
        failCurrentWithQueuedStop(connection, operation, expected, code);
    }

    private void failCurrent(long connection, long operation,
            State expected, int code, boolean stop) {
        completeCurrent(connection, operation, expected, State.FAILED, code, stop);
    }

    private void failCurrentWithQueuedStop(long connection, long operation,
            State expected, int code) {
        Runnable cancellation;
        byte[] enrolled;
        List<byte[]> pending;
        long failedOperation;
        synchronized (this) {
            if (!isCurrentLocked(connection, operation, expected)) return;
            state = State.FAILED;
            operationGeneration = nextGeneration(operationGeneration);
            failedOperation = operationGeneration;
            featureClaimed = false;
            cancellation = takeTimeoutLocked();
            enrolled = enrolledFeature;
            enrolledFeature = null;
            pending = takePendingFeaturesLocked();
        }
        safeCancel(cancellation);
        zero(enrolled);
        zeroAll(pending);
        notifyListener(State.FAILED, code);
        enqueueFailedCleanup(connection, failedOperation);
    }

    private void enqueueFailedCleanup(final long connection,
            final long failedOperation) {
        enqueueCleanup(new Runnable() {
            @Override public void run() {
                synchronized (PalmTestController.this) {
                    if (!isCurrentLocked(connection, failedOperation, State.FAILED)) return;
                }
                tryStop();
            }
        });
    }

    private void completeCurrent(long connection, long operation, State expected,
            State terminal, int code, boolean stop) {
        Runnable cancellation;
        byte[] enrolled = null;
        List<byte[]> pending = null;
        synchronized (this) {
            if (!isCurrentLocked(connection, operation, expected)) return;
            state = terminal;
            operationGeneration = nextGeneration(operationGeneration);
            featureClaimed = false;
            cancellation = takeTimeoutLocked();
            if (terminal == State.FAILED) {
                enrolled = enrolledFeature;
                enrolledFeature = null;
                pending = takePendingFeaturesLocked();
            }
        }
        safeCancel(cancellation);
        zero(enrolled);
        zeroAll(pending);
        if (stop) tryStop();
        notifyListener(terminal, code);
    }

    private boolean enqueueCallback(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RuntimeException ignored) {
            failWithoutWorker(ERROR_EXECUTOR);
            return false;
        }
    }

    private void enqueueWorker(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException ignored) {
            failWithoutWorker(ERROR_EXECUTOR);
        }
    }

    private void enqueueCleanup(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException ignored) {
            // Native cleanup may only run on the supplied worker.
        }
    }

    private void failWithoutWorker(int code) {
        Runnable cancellation;
        byte[] enrolled;
        List<byte[]> pending;
        synchronized (this) {
            if (state == State.CLOSED || state == State.FAILED) return;
            state = State.FAILED;
            operationGeneration = nextGeneration(operationGeneration);
            featureClaimed = false;
            cancellation = takeTimeoutLocked();
            enrolled = enrolledFeature;
            enrolledFeature = null;
            pending = takePendingFeaturesLocked();
        }
        safeCancel(cancellation);
        zero(enrolled);
        zeroAll(pending);
        notifyListener(State.FAILED, code);
    }

    private boolean isCurrent(long connection, long operation, State expected) {
        synchronized (this) {
            return isCurrentLocked(connection, operation, expected);
        }
    }

    private boolean isCurrentLocked(long connection, long operation, State expected) {
        return connectionGeneration == connection
                && operationGeneration == operation && state == expected;
    }

    private Runnable takeTimeoutLocked() {
        Runnable result = timeoutCancellation;
        timeoutCancellation = null;
        return result;
    }

    private List<byte[]> takePendingFeaturesLocked() {
        List<byte[]> result = new ArrayList<byte[]>(pendingFeatures.size());
        for (FeatureBox box : pendingFeatures) {
            if (box.bytes != null) {
                result.add(box.bytes);
                box.bytes = null;
            }
        }
        pendingFeatures.clear();
        return result;
    }

    private byte[] releaseFeature(FeatureBox box) {
        synchronized (this) {
            pendingFeatures.remove(box);
            byte[] result = box.bytes;
            box.bytes = null;
            return result;
        }
    }

    private boolean tryStop() {
        try {
            driver.stop();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private boolean safeClose() {
        try {
            driver.close();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private void safeCancel(Runnable cancellation) {
        if (cancellation == null) return;
        try {
            cancellation.run();
        } catch (RuntimeException ignored) {
            // A racing timeout still loses through the generation/state check.
        }
    }

    private void notifyListener(State deliveredState, int code) {
        try {
            listener.onState(deliveredState, code);
        } catch (RuntimeException ignored) {
            // Listener failures cannot corrupt the hardware session state.
        }
    }

    private static State expectedState(Operation operation) {
        if (operation == Operation.CONNECT) return State.CONNECTING;
        if (operation == Operation.ENROLL) return State.ENROLLING;
        return State.VERIFYING;
    }

    private static long nextGeneration(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static void zeroAll(List<byte[]> buffers) {
        if (buffers == null) return;
        for (byte[] buffer : buffers) zero(buffer);
    }
}
