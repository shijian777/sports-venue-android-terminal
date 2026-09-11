package com.codex.lockertest.bootstrap;

import com.codex.lockertest.ui.TerminalReadinessSource;

/** Lifecycle boundary for variant-specific server bootstrap behavior. */
public interface BootstrapRuntime extends AutoCloseable {
    interface Listener {
        void onSnapshot(BootstrapSnapshot snapshot);
    }

    long start(Listener listener);

    long restartBootstrap();

    void cancel();

    BootstrapSnapshot snapshot();

    TerminalReadinessSource readinessSource();

    @Override
    void close();
}
