package com.codex.lockertest.face;

import java.util.ArrayDeque;
import java.util.EnumSet;

/** Pure four-stage initialization and physical-attempt ownership state holder. */
public final class FaceRuntimeStateMachine {
    public static final long INIT_TIMEOUT_MILLIS = 15000L;

    public enum State {
        UNINITIALIZED, INITIALIZING, READY, FAILED, RELEASED
    }

    public enum ModelStage {
        TRACKER_MODEL, DETECTOR_MODEL, QUALITY_MODELS, BEST_IMAGE_MODEL
    }

    public enum Directive {
        START_NOW, QUEUE_VENDOR_BARRIER_THEN_CLEAN, START_AFTER_CLEANUP, NO_OP
    }

    enum AnalysisStart {
        RUN_NATIVE, CLEANUP_ONLY, NO_OP
    }

    interface TaskQueue {
        void execute(Runnable task);
    }

    interface EventConsumer<E> {
        void accept(E event);
    }

    /**
     * Caller-lock-bound FIFO drain seam. Enqueue and drainer ownership are claimed
     * under the owner's state lock; delivery runs outside that lock and reentrant
     * enqueues append to the same queue.
     */
    static final class OrderedDrainQueue<E> {
        private final Object ownerLock;
        private final ArrayDeque<E> pending = new ArrayDeque<E>();
        private boolean draining;

        OrderedDrainQueue(Object ownerLock) {
            if (ownerLock == null) {
                throw new IllegalArgumentException("ownerLock cannot be null");
            }
            this.ownerLock = ownerLock;
        }

        boolean enqueueLocked(E event) {
            if (!Thread.holdsLock(ownerLock)) {
                throw new IllegalStateException("owner lock is required");
            }
            if (event == null) throw new IllegalArgumentException("event cannot be null");
            pending.addLast(event);
            if (draining) return false;
            draining = true;
            return true;
        }

        void drain(EventConsumer<E> consumer) {
            if (consumer == null) throw new IllegalArgumentException("consumer cannot be null");
            while (true) {
                E event;
                synchronized (ownerLock) {
                    if (pending.isEmpty()) {
                        draining = false;
                        return;
                    }
                    event = pending.removeFirst();
                }
                try { consumer.accept(event); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    /**
     * Production-used ordering seam: a vendor barrier is itself enqueued from the
     * serialized runtime queue, so an in-progress model-submission task finishes first.
     */
    static final class RuntimeVendorDispatcher {
        private final TaskQueue runtimeQueue;
        private final TaskQueue vendorQueue;

        RuntimeVendorDispatcher(TaskQueue runtimeQueue, TaskQueue vendorQueue) {
            if (runtimeQueue == null || vendorQueue == null) {
                throw new IllegalArgumentException("queues cannot be null");
            }
            this.runtimeQueue = runtimeQueue;
            this.vendorQueue = vendorQueue;
        }

        boolean executeRuntime(Runnable task) {
            if (task == null) return false;
            try {
                runtimeQueue.execute(task);
                return true;
            } catch (RuntimeException | LinkageError rejected) {
                return false;
            }
        }

        boolean executeVendorAfterRuntime(Runnable vendorTask, Runnable rejected) {
            if (vendorTask == null || rejected == null) return false;
            return executeRuntime(() -> {
                try {
                    vendorQueue.execute(vendorTask);
                } catch (RuntimeException | LinkageError failure) {
                    runSafely(rejected);
                }
            });
        }

        private static void runSafely(Runnable action) {
            try { action.run(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    /** Epoch shared by final listener/analysis callback claims and release. */
    static final class CallbackEpoch {
        private long epoch = 1L;

        synchronized long capture() { return epoch; }

        synchronized void invalidate() {
            epoch = epoch == Long.MAX_VALUE ? 0L : epoch + 1L;
        }

        synchronized boolean tryClaim(long captured) {
            return captured == epoch;
        }
    }

    /**
     * Prevents any new public callback from starting after release closes the
     * runtime, and lets an external releaser wait for a callback that already
     * entered. The monitor is deliberately reentrant so a callback may call
     * release without waiting on itself.
     */
    static final class CallbackDeliveryFence {
        private final Object deliveryMonitor = new Object();
        private volatile boolean closed;

        boolean deliver(Runnable action) {
            if (action == null) throw new IllegalArgumentException("action cannot be null");
            if (closed) return false;
            synchronized (deliveryMonitor) {
                if (closed) return false;
                action.run();
                return true;
            }
        }

        void beginClose() {
            closed = true;
        }

        void awaitDrained() {
            synchronized (deliveryMonitor) {
                // Acquiring the monitor is the drain barrier.
            }
        }
    }

    /** Pure ownership gate used by each runtime analysis task. */
    static final class AnalysisGate {
        private final Runnable waitObserver;
        private boolean started;
        private boolean cancelled;
        private boolean cleanupComplete;
        private boolean notificationClaimed;
        private Thread runner;

        AnalysisGate() { this(() -> { }); }

        AnalysisGate(Runnable waitObserver) {
            this.waitObserver = waitObserver == null ? () -> { } : waitObserver;
        }

        synchronized AnalysisStart tryStart() {
            if (started || cleanupComplete) return AnalysisStart.NO_OP;
            started = true;
            runner = Thread.currentThread();
            return cancelled ? AnalysisStart.CLEANUP_ONLY : AnalysisStart.RUN_NATIVE;
        }

        synchronized void cancel() {
            cancelled = true;
        }

        void awaitCleanup() {
            boolean interrupted = false;
            synchronized (this) {
                if (cleanupComplete || Thread.currentThread() == runner) return;
                try { waitObserver.run(); }
                catch (RuntimeException | LinkageError ignored) { }
                while (!cleanupComplete) {
                    try {
                        wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        synchronized boolean completeCleanup() {
            if (cleanupComplete) return false;
            cleanupComplete = true;
            runner = null;
            notifyAll();
            return true;
        }

        synchronized boolean tryClaimNotification() {
            if (!cleanupComplete || cancelled || notificationClaimed) return false;
            notificationClaimed = true;
            return true;
        }
    }

    public static final class StartResult {
        private final long generation;
        private final boolean shouldStart;
        private final Directive directive;
        private final long physicalAttemptId;

        private StartResult(long generation, boolean shouldStart,
                Directive directive, long physicalAttemptId) {
            this.generation = generation;
            this.shouldStart = shouldStart;
            this.directive = directive;
            this.physicalAttemptId = physicalAttemptId;
        }

        public long generation() { return generation; }
        public boolean shouldStart() { return shouldStart; }
        public Directive directive() { return directive; }
        public long physicalAttemptId() { return physicalAttemptId; }
    }

    public static final class CleanupResult {
        private final Directive directive;
        private final long generation;
        private final long physicalAttemptId;

        private CleanupResult(Directive directive, long generation,
                long physicalAttemptId) {
            this.directive = directive;
            this.generation = generation;
            this.physicalAttemptId = physicalAttemptId;
        }

        public Directive directive() { return directive; }
        public long generation() { return generation; }
        public long physicalAttemptId() { return physicalAttemptId; }
    }

    public static final class ReleaseResult {
        private final Directive directive;
        private final long physicalAttemptId;

        private ReleaseResult(Directive directive, long physicalAttemptId) {
            this.directive = directive;
            this.physicalAttemptId = physicalAttemptId;
        }

        public Directive directive() { return directive; }
        public long physicalAttemptId() { return physicalAttemptId; }
    }

    private State state = State.UNINITIALIZED;
    private final EnumSet<ModelStage> completed = EnumSet.noneOf(ModelStage.class);
    private long generation;
    private long lastGeneration;
    private long currentPhysicalAttemptId;
    private long lastPhysicalAttemptId;
    private boolean cleanupPending;
    private boolean cleanupBarrierSubmitted;
    private boolean retryWaiting;
    private boolean nativeCleanupTerminalFailure;

    public FaceRuntimeStateMachine() { this(0L); }

    FaceRuntimeStateMachine(long lastGeneration) {
        if (lastGeneration < 0L) {
            throw new IllegalArgumentException("last generation cannot be negative");
        }
        this.lastGeneration = lastGeneration;
    }

    public synchronized State state() { return state; }

    public synchronized long currentGeneration() { return generation; }

    public synchronized long currentPhysicalAttemptId() {
        return currentPhysicalAttemptId;
    }

    public synchronized StartResult startInitialize() {
        if (state == State.INITIALIZING) {
            return new StartResult(generation, false, Directive.NO_OP,
                    currentPhysicalAttemptId);
        }
        if (state == State.READY || state == State.RELEASED
                || nativeCleanupTerminalFailure) return noStart();
        long nextGeneration = nextGeneration();
        if (nextGeneration == 0L) return noStart();
        generation = nextGeneration;
        completed.clear();
        state = State.INITIALIZING;

        if (currentPhysicalAttemptId != 0L) {
            if (cleanupPending) {
                state = State.FAILED;
                return noStart();
            }
            cleanupPending = true;
            cleanupBarrierSubmitted = false;
            retryWaiting = true;
            return new StartResult(generation, true,
                    Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                    currentPhysicalAttemptId);
        }
        long physical = allocatePhysicalAttempt();
        if (physical == 0L) return noStart();
        return new StartResult(generation, true, Directive.START_NOW, physical);
    }

    public synchronized boolean stageSucceeded(long callbackGeneration,
            ModelStage stage) {
        if (stage == null || callbackGeneration != generation
                || state != State.INITIALIZING || retryWaiting
                || completed.contains(stage)) return false;
        completed.add(stage);
        if (completed.size() == ModelStage.values().length) state = State.READY;
        return true;
    }

    public synchronized boolean stageFailed(long callbackGeneration,
            ModelStage stage) {
        if (stage == null || callbackGeneration != generation
                || state != State.INITIALIZING || retryWaiting
                || completed.contains(stage)) return false;
        state = State.FAILED;
        return true;
    }

    public synchronized boolean timeout(long callbackGeneration) {
        if (callbackGeneration != generation || state != State.INITIALIZING) {
            return false;
        }
        state = State.FAILED;
        retryWaiting = false;
        return true;
    }

    public synchronized boolean startDispatchRejected(long rejectedGeneration,
            long physicalAttemptId) {
        if (state != State.INITIALIZING || retryWaiting
                || rejectedGeneration != generation
                || physicalAttemptId != currentPhysicalAttemptId) return false;
        state = State.FAILED;
        return true;
    }

    /** Aborts validation/timer setup before either model start or cleanup barrier. */
    public synchronized boolean abortStart(long abortedGeneration,
            long physicalAttemptId) {
        if (state != State.INITIALIZING || abortedGeneration != generation
                || physicalAttemptId != currentPhysicalAttemptId) return false;
        state = State.FAILED;
        completed.clear();
        if (retryWaiting) {
            retryWaiting = false;
            cleanupPending = false;
            cleanupBarrierSubmitted = false;
        } else {
            currentPhysicalAttemptId = 0L;
            cleanupPending = false;
            cleanupBarrierSubmitted = false;
        }
        return true;
    }

    /** Claims the one runtime-to-vendor barrier submission for pending cleanup. */
    public synchronized boolean claimCleanupBarrierSubmission(long physicalAttemptId) {
        if (physicalAttemptId == 0L
                || physicalAttemptId != currentPhysicalAttemptId
                || !cleanupPending || cleanupBarrierSubmitted) return false;
        cleanupBarrierSubmitted = true;
        return true;
    }

    public synchronized boolean failAttempt(long failedGeneration) {
        if (state != State.INITIALIZING || retryWaiting
                || failedGeneration != generation) return false;
        state = State.FAILED;
        return true;
    }

    public synchronized boolean cleanupBarrierSubmissionRejected(long physicalAttemptId) {
        if (physicalAttemptId == 0L || physicalAttemptId != currentPhysicalAttemptId
                || !cleanupPending || !cleanupBarrierSubmitted) return false;
        cleanupPending = false;
        cleanupBarrierSubmitted = false;
        retryWaiting = false;
        if (state == State.RELEASED) {
            currentPhysicalAttemptId = 0L;
            nativeCleanupTerminalFailure = true;
        } else {
            state = State.FAILED;
        }
        return true;
    }

    /** Compatibility alias for the original pure-state test surface. */
    public synchronized boolean cleanupBarrierRejected(long physicalAttemptId) {
        return cleanupBarrierSubmissionRejected(physicalAttemptId);
    }

    /** Native cleanup was entered and therefore may never be repeated. */
    public synchronized boolean cleanupAttemptFailed(long physicalAttemptId) {
        if (physicalAttemptId == 0L || physicalAttemptId != currentPhysicalAttemptId
                || !cleanupPending || !cleanupBarrierSubmitted) return false;
        currentPhysicalAttemptId = 0L;
        cleanupPending = false;
        cleanupBarrierSubmitted = false;
        retryWaiting = false;
        nativeCleanupTerminalFailure = true;
        if (state != State.RELEASED) state = State.FAILED;
        return true;
    }

    /** Called only after the vendor barrier and native cleanup both completed. */
    public synchronized CleanupResult cleanupCompleted(long physicalAttemptId) {
        if (physicalAttemptId == 0L || physicalAttemptId != currentPhysicalAttemptId
                || !cleanupPending || !cleanupBarrierSubmitted) return noCleanup();
        currentPhysicalAttemptId = 0L;
        cleanupPending = false;
        cleanupBarrierSubmitted = false;
        if (state == State.RELEASED || !retryWaiting) {
            retryWaiting = false;
            return noCleanup();
        }
        retryWaiting = false;
        long physical = allocatePhysicalAttempt();
        if (physical == 0L) return noCleanup();
        return new CleanupResult(Directive.START_AFTER_CLEANUP,
                generation, physical);
    }

    public synchronized boolean mayAnalyze() { return state == State.READY; }

    /** Claims the cleanup barrier for the current physical attempt at most once. */
    public synchronized ReleaseResult release() {
        if (state != State.RELEASED) {
            state = State.RELEASED;
            generation = 0L;
            retryWaiting = false;
            completed.clear();
        }
        if (currentPhysicalAttemptId == 0L) {
            return new ReleaseResult(Directive.NO_OP, 0L);
        }
        if (!cleanupPending) {
            cleanupPending = true;
            cleanupBarrierSubmitted = false;
        }
        if (cleanupBarrierSubmitted) {
            return new ReleaseResult(Directive.NO_OP, 0L);
        }
        return new ReleaseResult(Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN,
                currentPhysicalAttemptId);
    }

    private long nextGeneration() {
        if (lastGeneration == Long.MAX_VALUE) {
            state = State.FAILED;
            return 0L;
        }
        return ++lastGeneration;
    }

    private long allocatePhysicalAttempt() {
        if (lastPhysicalAttemptId == Long.MAX_VALUE) {
            state = State.FAILED;
            retryWaiting = false;
            return 0L;
        }
        currentPhysicalAttemptId = ++lastPhysicalAttemptId;
        cleanupPending = false;
        cleanupBarrierSubmitted = false;
        return currentPhysicalAttemptId;
    }

    private StartResult noStart() {
        return new StartResult(0L, false, Directive.NO_OP, 0L);
    }

    private CleanupResult noCleanup() {
        return new CleanupResult(Directive.NO_OP, 0L, 0L);
    }
}
