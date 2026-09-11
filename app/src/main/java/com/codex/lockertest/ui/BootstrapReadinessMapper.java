package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BootstrapSnapshot;

/** Converts internal bootstrap diagnostics into customer-safe terminal states. */
public final class BootstrapReadinessMapper {
    public TerminalReadiness map(BootstrapSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Bootstrap snapshot is required");
        }
        switch (snapshot.phase()) {
            case READY_READ_ONLY:
                return TerminalReadiness.readyReadOnly();
            case CANCELLED:
            case CLOSED:
                return TerminalReadiness.bootstrapStopped();
            case BLOCKED:
                return mapFailure(snapshot.failureReason());
            case IDLE:
            case READING_SERIAL:
            case CHECKING_DEVICE:
            case LOADING_BASE_SETTING:
            case LOADING_BASIC_DATA:
            case RETRY_WAIT:
            default:
                return TerminalReadiness.serverConnecting();
        }
    }

    private static TerminalReadiness mapFailure(BootstrapSnapshot.FailureReason reason) {
        switch (reason) {
            case CONFIGURATION:
            case KEY_MISSING:
                return TerminalReadiness.serverNotConfigured();
            case SERIAL_UNAVAILABLE:
                return TerminalReadiness.deviceSerialUnavailable();
            case REMOTE_REJECTED:
                // A generic rejection can also mean bad signing or a server policy.
                // Only a documented registration-specific result may say "未登记".
                return TerminalReadiness.serverRequestRejected();
            case TIMEOUT:
                return TerminalReadiness.serverTimeout();
            case NETWORK:
                return TerminalReadiness.networkUnavailable();
            case HTTP:
                return TerminalReadiness.serverUnavailable();
            case TLS:
            case REDIRECT:
                return TerminalReadiness.serverSecurityError();
            case CANCELLED:
                return TerminalReadiness.bootstrapStopped();
            case CLOCK_INVALID:
                return TerminalReadiness.deviceClockInvalid();
            case INVALID_RESPONSE:
            case CONTRACT:
            case NONE:
            default:
                return TerminalReadiness.serverResponseInvalid();
        }
    }
}
