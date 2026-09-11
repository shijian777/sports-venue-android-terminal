package com.codex.lockertest.business;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Prevents a backing store's process-local mutation from becoming readable
 * before durable persistence succeeds. A persistence failure quarantines this
 * store instance for the rest of the process lifetime.
 */
public final class FailClosedScannerSourceStore implements ScannerSourceRegistry.Store {
    private enum Phase { READY, WRITING, QUARANTINED }

    private static final class State {
        private final Phase phase;

        private State(Phase phase) {
            this.phase = phase;
        }
    }

    private static final State QUARANTINED = new State(Phase.QUARANTINED);
    private final ScannerSourceRegistry.Store backing;
    private final AtomicReference<State> state = new AtomicReference<>(new State(Phase.READY));

    public FailClosedScannerSourceStore(ScannerSourceRegistry.Store backing) {
        if (backing == null) throw new IllegalArgumentException("Backing source storage required");
        this.backing = backing;
    }

    @Override public int read(String key) {
        State before = state.get();
        if (before.phase != Phase.READY) return 0;
        int value = backing.read(key);
        return state.get() == before ? value : 0;
    }

    @Override public boolean write(String key, int type) {
        State before = state.get();
        if (before.phase != Phase.READY) return false;
        State writing = new State(Phase.WRITING);
        if (!state.compareAndSet(before, writing)) return false;

        final boolean persisted;
        try {
            persisted = backing.write(key, type);
        } catch (RuntimeException failure) {
            state.set(QUARANTINED);
            return false;
        }
        if (!persisted) {
            state.set(QUARANTINED);
            return false;
        }
        state.set(new State(Phase.READY));
        return true;
    }
}
