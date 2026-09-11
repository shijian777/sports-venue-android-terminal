package com.codex.lockertest.bootstrap;

import com.codex.lockertest.ui.BootstrapReadinessMapper;
import com.codex.lockertest.ui.MutableTerminalReadinessSource;
import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.TerminalReadinessSource;

/** Production runtime adapter for the read-only bootstrap chain. */
final class ProductionBootstrapRuntime implements BootstrapRuntime {
    private final Object lock = new Object();
    private final BootstrapReadinessMapper mapper = new BootstrapReadinessMapper();
    private final MutableTerminalReadinessSource readinessSource;
    private final DeviceBootstrapCoordinator coordinator;
    private final TerminalReadiness blockedReadiness;
    private final BootstrapSnapshot.FailureReason blockedReason;
    private final AutoCloseable[] ownedResources;

    private BootstrapSnapshot snapshot;
    private Listener listener;
    private long blockedGeneration;
    private boolean closed;

    ProductionBootstrapRuntime(
            DeviceSerialProvider serialProvider,
            BootstrapService service,
            BootstrapScheduler scheduler) {
        this(serialProvider, service, scheduler, new AutoCloseable[0]);
    }

    ProductionBootstrapRuntime(
            DeviceSerialProvider serialProvider,
            BootstrapService service,
            BootstrapScheduler scheduler,
            AutoCloseable... ownedResources) {
        if (serialProvider == null || service == null || scheduler == null) {
            throw new IllegalArgumentException("Bootstrap dependencies are required");
        }
        if (ownedResources == null) {
            throw new IllegalArgumentException("Bootstrap resources are required");
        }
        this.ownedResources = ownedResources.clone();
        for (AutoCloseable resource : this.ownedResources) {
            if (resource == null) {
                throw new IllegalArgumentException("Bootstrap resources are required");
            }
        }
        readinessSource = new MutableTerminalReadinessSource(
                TerminalReadiness.serverConnecting());
        blockedReadiness = null;
        blockedReason = null;
        coordinator = new DeviceBootstrapCoordinator(
                serialProvider, service, scheduler, this::accept);
        snapshot = coordinator.snapshot();
    }

    private ProductionBootstrapRuntime(
            TerminalReadiness blockedReadiness,
            BootstrapSnapshot.FailureReason blockedReason) {
        if (blockedReadiness == null || blockedReason == null) {
            throw new IllegalArgumentException("Blocked bootstrap state is required");
        }
        readinessSource = new MutableTerminalReadinessSource(blockedReadiness);
        this.blockedReadiness = blockedReadiness;
        this.blockedReason = blockedReason;
        ownedResources = new AutoCloseable[0];
        coordinator = null;
        snapshot = BootstrapSnapshot.state(
                0L,
                BootstrapSnapshot.Phase.IDLE,
                BootstrapSnapshot.Endpoint.NONE,
                BootstrapSnapshot.FailureReason.NONE,
                "********");
    }

    static BootstrapRuntime blocked(
            TerminalReadiness readiness,
            BootstrapSnapshot.FailureReason reason) {
        return new ProductionBootstrapRuntime(readiness, reason);
    }

    @Override
    public long start(Listener nextListener) {
        if (nextListener == null) {
            throw new IllegalArgumentException("Bootstrap listener is required");
        }
        synchronized (lock) {
            ensureOpenLocked();
            listener = nextListener;
        }
        if (coordinator != null) {
            return coordinator.start();
        }
        return publishBlocked();
    }

    @Override
    public long restartBootstrap() {
        synchronized (lock) {
            ensureOpenLocked();
            if (listener == null) {
                throw new IllegalStateException("Bootstrap runtime has not started");
            }
        }
        if (coordinator != null) {
            return coordinator.start();
        }
        return publishBlocked();
    }

    @Override
    public void cancel() {
        if (coordinator != null) {
            coordinator.cancel();
            return;
        }
        Listener callback;
        BootstrapSnapshot next;
        synchronized (lock) {
            if (closed) return;
            blockedGeneration++;
            next = BootstrapSnapshot.state(
                    blockedGeneration,
                    BootstrapSnapshot.Phase.CANCELLED,
                    BootstrapSnapshot.Endpoint.NONE,
                    BootstrapSnapshot.FailureReason.CANCELLED,
                    "********");
            snapshot = next;
            readinessSource.update(TerminalReadiness.bootstrapStopped());
            callback = listener;
        }
        notifyListener(callback, next);
    }

    @Override
    public BootstrapSnapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    @Override
    public TerminalReadinessSource readinessSource() {
        return readinessSource;
    }

    @Override
    public void close() {
        Listener callback = null;
        BootstrapSnapshot next = null;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (coordinator == null) {
                blockedGeneration++;
                next = BootstrapSnapshot.state(
                        blockedGeneration,
                        BootstrapSnapshot.Phase.CLOSED,
                        BootstrapSnapshot.Endpoint.NONE,
                        BootstrapSnapshot.FailureReason.NONE,
                        "********");
                snapshot = next;
                readinessSource.update(TerminalReadiness.bootstrapStopped());
                callback = listener;
            }
        }
        if (coordinator != null) {
            coordinator.close();
            closeOwnedResources();
        } else {
            notifyListener(callback, next);
        }
    }

    private long publishBlocked() {
        Listener callback;
        BootstrapSnapshot next;
        synchronized (lock) {
            ensureOpenLocked();
            blockedGeneration++;
            next = BootstrapSnapshot.state(
                    blockedGeneration,
                    BootstrapSnapshot.Phase.BLOCKED,
                    BootstrapSnapshot.Endpoint.NONE,
                    blockedReason,
                    "********");
            snapshot = next;
            readinessSource.update(blockedReadiness);
            callback = listener;
        }
        notifyListener(callback, next);
        return next.generation();
    }

    private void accept(BootstrapSnapshot next) {
        Listener callback;
        synchronized (lock) {
            if (closed && next.phase() != BootstrapSnapshot.Phase.CLOSED) return;
            snapshot = next;
            readinessSource.update(mapper.map(next));
            callback = listener;
        }
        notifyListener(callback, next);
    }

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Bootstrap runtime is closed");
        }
    }

    private void closeOwnedResources() {
        for (int index = ownedResources.length - 1; index >= 0; index--) {
            try {
                ownedResources[index].close();
            } catch (Exception ignored) {
                // Runtime state is already closed; cleanup errors cannot reopen it.
            }
        }
    }

    private static void notifyListener(Listener callback, BootstrapSnapshot next) {
        if (callback == null || next == null) return;
        try {
            callback.onSnapshot(next);
        } catch (RuntimeException ignored) {
            // UI callbacks cannot change the authoritative bootstrap state.
        }
    }
}
