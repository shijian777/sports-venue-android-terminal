package com.codex.lockertest.integration;

import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialSessionState;

/**
 * Process-level policy for the one passive startup open and explicit customer attempts.
 */
public final class PersistentSerialConnectionPolicy {
    public enum Action {
        NONE,
        OPEN_DEFAULTS,
        CLOSE_FOR_DEFAULTS,
        REUSE,
        WAIT,
        FAIL
    }

    private static final PersistentSerialConnectionPolicy SHARED =
            new PersistentSerialConnectionPolicy();

    private boolean mainStartConsumed;

    public static PersistentSerialConnectionPolicy shared() {
        return SHARED;
    }

    public synchronized Action onMainStarted(SerialSessionState.Phase phase) {
        if (mainStartConsumed) {
            return Action.NONE;
        }
        mainStartConsumed = true;
        return actionForPhase(phase);
    }

    /** A successful explicit Admin close must win over any pending first-Main auto-open. */
    public synchronized void onManualCloseAccepted() {
        mainStartConsumed = true;
    }

    public Attempt newCustomerAttempt() {
        return new Attempt();
    }

    public static final class Attempt {
        private boolean closeIssued;
        private boolean defaultOpenIssued;
        private boolean terminalFailure;
        private boolean cancelled;

        /**
         * Compatibility bridge for the pre-exact-config MainActivity. Task 4D migrates
         * the caller to {@link #reconcile(SerialSessionState.Phase, SerialConfig)}.
         */
        @Deprecated
        public synchronized Action reconcile(SerialSessionState.Phase phase) {
            if (cancelled) {
                return Action.NONE;
            }
            if (terminalFailure) {
                return Action.FAIL;
            }
            if (phase == null || phase == SerialSessionState.Phase.DISPOSED) {
                return fail();
            }
            switch (phase) {
                case OPEN:
                    return Action.REUSE;
                case OPENING:
                case CLOSING:
                    return Action.WAIT;
                case CLOSED:
                    if (defaultOpenIssued) {
                        return fail();
                    }
                    defaultOpenIssued = true;
                    return Action.OPEN_DEFAULTS;
                case DISPOSED:
                default:
                    return fail();
            }
        }

        public synchronized Action reconcile(
                SerialSessionState.Phase phase,
                SerialConfig activeConfig) {
            return reconcileExact(phase, activeConfig, false);
        }

        public synchronized Action onConnectionEvent(
                SerialSessionState.Phase phase,
                SerialConfig activeConfig) {
            if (cancelled) {
                return Action.NONE;
            }
            if (terminalFailure) {
                return Action.FAIL;
            }
            if (phase == SerialSessionState.Phase.CLOSING) {
                return fail();
            }
            if (phase == SerialSessionState.Phase.OPEN
                    && !SerialConfig.defaults().equals(activeConfig)
                    && closeIssued) {
                return fail();
            }
            return reconcileExact(phase, activeConfig, true);
        }

        public synchronized Action onCloseRequestResult(boolean accepted) {
            if (cancelled) {
                return Action.NONE;
            }
            if (terminalFailure) {
                return Action.FAIL;
            }
            if (!closeIssued || !accepted) {
                return fail();
            }
            return Action.WAIT;
        }

        /** Compatibility bridge retained until Task 4D migrates MainActivity. */
        @Deprecated
        public synchronized Action onCloseCompleted(SerialSessionState.Phase phase) {
            return reconcile(phase);
        }

        public synchronized void cancel() {
            cancelled = true;
        }

        private Action reconcileExact(
                SerialSessionState.Phase phase,
                SerialConfig activeConfig,
                boolean connectionEvent) {
            if (cancelled) {
                return Action.NONE;
            }
            if (terminalFailure) {
                return Action.FAIL;
            }
            if (phase == null || phase == SerialSessionState.Phase.DISPOSED) {
                return fail();
            }
            switch (phase) {
                case OPEN:
                    if (SerialConfig.defaults().equals(activeConfig)) {
                        return Action.REUSE;
                    }
                    if (defaultOpenIssued) {
                        return fail();
                    }
                    if (closeIssued) {
                        return connectionEvent ? fail() : Action.WAIT;
                    }
                    closeIssued = true;
                    return Action.CLOSE_FOR_DEFAULTS;
                case OPENING:
                case CLOSING:
                    return Action.WAIT;
                case CLOSED:
                    if (defaultOpenIssued) {
                        return fail();
                    }
                    defaultOpenIssued = true;
                    return Action.OPEN_DEFAULTS;
                case DISPOSED:
                default:
                    return fail();
            }
        }

        private Action fail() {
            terminalFailure = true;
            return Action.FAIL;
        }
    }

    private static Action actionForPhase(SerialSessionState.Phase phase) {
        if (phase == null || phase == SerialSessionState.Phase.DISPOSED) {
            return Action.FAIL;
        }
        switch (phase) {
            case CLOSED:
                return Action.OPEN_DEFAULTS;
            case OPEN:
                return Action.REUSE;
            case OPENING:
            case CLOSING:
                return Action.WAIT;
            case DISPOSED:
            default:
                return Action.FAIL;
        }
    }
}
