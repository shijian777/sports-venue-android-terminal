package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BootstrapRuntime;

/** Keeps customer, bootstrap-retry, and administrator lanes independent. */
public final class BootstrapUiSessionGate {
    private final BootstrapRuntime runtime;

    public BootstrapUiSessionGate(BootstrapRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("Bootstrap runtime is required");
        }
        this.runtime = runtime;
    }

    public boolean customerActionsEnabled() {
        TerminalReadiness readiness = runtime.readinessSource().current();
        return readiness != null && readiness.customerActionsEnabled();
    }

    public boolean adminActionsEnabled() {
        return true;
    }

    public boolean canRetryBootstrap() {
        return BootstrapHomePresentation.retryable(runtime.snapshot());
    }

    public long restartBootstrap() {
        if (!canRetryBootstrap()) return -1L;
        return runtime.restartBootstrap();
    }
}
