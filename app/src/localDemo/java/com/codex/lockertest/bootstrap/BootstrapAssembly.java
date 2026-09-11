package com.codex.lockertest.bootstrap;

import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.TerminalReadinessSource;

/** Local demo composition root with no worker thread or network dependency. */
public final class BootstrapAssembly {
    private BootstrapAssembly() { }

    public static BootstrapRuntime create(Object context) {
        return new LocalDemoRuntime();
    }

    private static final class LocalDemoRuntime implements BootstrapRuntime {
        private final TerminalReadiness readiness = TerminalReadiness.localDemoReady();
        private BootstrapSnapshot snapshot = state(0L, BootstrapSnapshot.Phase.IDLE,
                BootstrapSnapshot.FailureReason.NONE);
        private Listener listener;
        private long generation;
        private boolean closed;

        @Override
        public synchronized long start(Listener nextListener) {
            if (nextListener == null) {
                throw new IllegalArgumentException("Bootstrap listener is required");
            }
            ensureOpen();
            listener = nextListener;
            return publish(BootstrapSnapshot.Phase.IDLE,
                    BootstrapSnapshot.FailureReason.NONE);
        }

        @Override
        public synchronized long restartBootstrap() {
            ensureOpen();
            if (listener == null) {
                throw new IllegalStateException("Bootstrap runtime has not started");
            }
            return publish(BootstrapSnapshot.Phase.IDLE,
                    BootstrapSnapshot.FailureReason.NONE);
        }

        @Override
        public synchronized void cancel() {
            if (closed) return;
            publish(BootstrapSnapshot.Phase.CANCELLED,
                    BootstrapSnapshot.FailureReason.CANCELLED);
        }

        @Override
        public synchronized BootstrapSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public TerminalReadinessSource readinessSource() {
            return readiness;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            publish(BootstrapSnapshot.Phase.CLOSED, BootstrapSnapshot.FailureReason.NONE);
        }

        private long publish(
                BootstrapSnapshot.Phase phase,
                BootstrapSnapshot.FailureReason reason) {
            generation++;
            snapshot = state(generation, phase, reason);
            if (listener != null) listener.onSnapshot(snapshot);
            return generation;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Bootstrap runtime is closed");
        }

        private static BootstrapSnapshot state(
                long generation,
                BootstrapSnapshot.Phase phase,
                BootstrapSnapshot.FailureReason reason) {
            return BootstrapSnapshot.state(
                    generation, phase, BootstrapSnapshot.Endpoint.NONE, reason, "********");
        }
    }
}
