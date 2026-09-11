package com.codex.lockertest.ui;

/**
 * Small, variant-selected execution boundary for customer-only effects.
 *
 * <p>The boundary owns no workflow, Android, network, or serial state. It only decides whether
 * the supplied delegate may execute for the terminal readiness selected at startup.</p>
 */
public final class CustomerActionBoundary {
    public enum Effect {
        PHONE_CREDENTIAL,
        SCANNER_CREDENTIAL,
        FACE,
        PALM,
        HOME_ENROLLMENT,
        RETURN_JOURNEY,
        RETRY,
        RESUME,
        ASYNC_CALLBACK,
        DISCOVERY,
        AUTHORIZATION,
        RETURN_SERVICE,
        SERIAL_CONNECT,
        SERIAL_SEND
    }

    @FunctionalInterface
    public interface Action {
        void run();
    }

    @FunctionalInterface
    public interface Call<T> {
        T call();
    }

    private final TerminalReadinessSource readinessSource;

    public CustomerActionBoundary(TerminalReadinessSource readinessSource) {
        if (readinessSource == null) {
            throw new IllegalArgumentException("Terminal readiness is required");
        }
        this.readinessSource = readinessSource;
    }

    public boolean customerActionsEnabled() {
        TerminalReadiness readiness = readinessSource.current();
        return readiness != null && readiness.customerActionsEnabled();
    }

    public boolean run(Effect effect, Action delegate) {
        requireEffect(effect);
        if (delegate == null) {
            throw new IllegalArgumentException("Customer action delegate is required");
        }
        if (!customerActionsEnabled()) {
            return false;
        }
        delegate.run();
        return true;
    }

    public <T> T call(Effect effect, Call<T> delegate, T unavailableValue) {
        requireEffect(effect);
        if (delegate == null) {
            throw new IllegalArgumentException("Customer call delegate is required");
        }
        if (!customerActionsEnabled()) {
            return unavailableValue;
        }
        return delegate.call();
    }

    private static void requireEffect(Effect effect) {
        if (effect == null) {
            throw new IllegalArgumentException("Customer effect is required");
        }
    }
}
