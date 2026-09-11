package com.codex.lockertest.ui;

/** Thread-visible readiness source updated by bootstrap state changes. */
public final class MutableTerminalReadinessSource implements TerminalReadinessSource {
    private volatile TerminalReadiness current;

    public MutableTerminalReadinessSource(TerminalReadiness initial) {
        update(initial);
    }

    public void update(TerminalReadiness next) {
        if (next == null) {
            throw new IllegalArgumentException("Terminal readiness is required");
        }
        current = next;
    }

    @Override
    public TerminalReadiness current() {
        return current;
    }
}
