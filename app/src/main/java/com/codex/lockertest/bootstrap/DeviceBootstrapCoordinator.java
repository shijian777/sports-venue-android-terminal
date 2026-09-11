package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

import java.util.concurrent.Future;

/**
 * Runs the read-only bootstrap chain with one generation barrier and endpoint-local retry.
 */
public final class DeviceBootstrapCoordinator implements AutoCloseable {
    public interface Observer {
        void onSnapshot(BootstrapSnapshot snapshot);
    }

    public static final long WATCHDOG_MILLIS = 15_000L;
    public static final long RETRY_DELAY_MILLIS = 250L;

    private final Object lock = new Object();
    private final DeviceSerialProvider serialProvider;
    private final BootstrapService service;
    private final BootstrapScheduler scheduler;
    private final Observer observer;

    private long generation;
    private boolean closed;
    private Future<?> workerFuture;
    private Future<?> retryFuture;
    private Future<?> watchdogFuture;
    private CallToken currentToken;
    private DeviceRegistration registration;
    private BaseSettingSnapshot baseSetting;
    private String serialDiagnostic = "********";
    private BootstrapSnapshot snapshot = BootstrapSnapshot.state(
            0L,
            BootstrapSnapshot.Phase.IDLE,
            BootstrapSnapshot.Endpoint.NONE,
            BootstrapSnapshot.FailureReason.NONE,
            serialDiagnostic);

    public DeviceBootstrapCoordinator(
            DeviceSerialProvider serialProvider,
            BootstrapService service,
            BootstrapScheduler scheduler,
            Observer observer) {
        if (serialProvider == null || service == null
                || scheduler == null || observer == null) {
            throw new IllegalArgumentException("Missing bootstrap dependency");
        }
        this.serialProvider = serialProvider;
        this.service = service;
        this.scheduler = scheduler;
        this.observer = observer;
    }

    public long start() {
        synchronized (lock) {
            if (closed) return generation;
            generation++;
            invalidateOwnedWorkLocked(CallToken.Reason.CANCELLED);
            registration = null;
            baseSetting = null;
            serialDiagnostic = "********";
            long startedGeneration = generation;
            publishLocked(BootstrapSnapshot.state(
                    generation,
                    BootstrapSnapshot.Phase.READING_SERIAL,
                    BootstrapSnapshot.Endpoint.NONE,
                    BootstrapSnapshot.FailureReason.NONE,
                    serialDiagnostic));
            try {
                Future<?> submitted = scheduler.submit(
                        () -> runInitial(startedGeneration));
                if (submitted == null) throw new IllegalStateException("Missing worker future");
                workerFuture = submitted;
            } catch (RuntimeException rejected) {
                blockLocked(
                        startedGeneration,
                        BootstrapSnapshot.Endpoint.NONE,
                        BootstrapSnapshot.FailureReason.CONFIGURATION);
            }
            return generation;
        }
    }

    public void cancel() {
        synchronized (lock) {
            if (closed) return;
            generation++;
            invalidateOwnedWorkLocked(CallToken.Reason.CANCELLED);
            registration = null;
            baseSetting = null;
            publishLocked(BootstrapSnapshot.state(
                    generation,
                    BootstrapSnapshot.Phase.CANCELLED,
                    BootstrapSnapshot.Endpoint.NONE,
                    BootstrapSnapshot.FailureReason.CANCELLED,
                    serialDiagnostic));
        }
    }

    public BootstrapSnapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            invalidateOwnedWorkLocked(CallToken.Reason.CANCELLED);
            registration = null;
            baseSetting = null;
            publishLocked(BootstrapSnapshot.state(
                    generation,
                    BootstrapSnapshot.Phase.CLOSED,
                    BootstrapSnapshot.Endpoint.NONE,
                    BootstrapSnapshot.FailureReason.NONE,
                    serialDiagnostic));
        }
    }

    private void runInitial(long expectedGeneration) {
        try {
            if (!advanceIfCurrent(
                    expectedGeneration, BootstrapSnapshot.Phase.READING_SERIAL)) {
                return;
            }
            DeviceSerial serial;
            try {
                serial = serialProvider.read();
            } catch (RuntimeException unavailable) {
                block(
                        expectedGeneration,
                        BootstrapSnapshot.Phase.READING_SERIAL,
                        BootstrapSnapshot.Endpoint.NONE,
                        BootstrapSnapshot.FailureReason.SERIAL_UNAVAILABLE);
                return;
            }
            if (!advanceIfCurrent(
                    expectedGeneration, BootstrapSnapshot.Phase.READING_SERIAL)) {
                return;
            }
            if (serial == null || !serial.isAvailable() || serial.value() == null) {
                String diagnostic = serial == null ? "********" : serial.diagnostic();
                synchronized (lock) {
                    if (!isCurrentLocked(expectedGeneration,
                            BootstrapSnapshot.Phase.READING_SERIAL)) return;
                    serialDiagnostic = diagnostic;
                    blockLocked(
                            expectedGeneration,
                            BootstrapSnapshot.Endpoint.NONE,
                            BootstrapSnapshot.FailureReason.SERIAL_UNAVAILABLE);
                }
                return;
            }
            synchronized (lock) {
                if (!isCurrentLocked(expectedGeneration,
                        BootstrapSnapshot.Phase.READING_SERIAL)) return;
                serialDiagnostic = serial.diagnostic();
                publishLocked(BootstrapSnapshot.state(
                        expectedGeneration,
                        BootstrapSnapshot.Phase.CHECKING_DEVICE,
                        BootstrapSnapshot.Endpoint.CHECK_DEVICE,
                        BootstrapSnapshot.FailureReason.NONE,
                        serialDiagnostic));
            }
            runCheck(expectedGeneration, serial.value(), 1);
        } finally {
            synchronized (lock) {
                if (generation == expectedGeneration) workerFuture = null;
            }
        }
    }

    private void runCheck(long expectedGeneration, String serial, int attempt) {
        ApiResult<DeviceRegistration> result = invokeWithWatchdog(
                expectedGeneration,
                BootstrapSnapshot.Phase.CHECKING_DEVICE,
                token -> service.checkDevice(serial, token));
        if (result == null || !advanceIfCurrent(
                expectedGeneration, BootstrapSnapshot.Phase.CHECKING_DEVICE)) {
            return;
        }
        if (!result.isSuccess()) {
            handleFailure(
                    expectedGeneration,
                    BootstrapSnapshot.Phase.CHECKING_DEVICE,
                    BootstrapSnapshot.Endpoint.CHECK_DEVICE,
                    result.failure(),
                    attempt,
                    () -> runCheck(expectedGeneration, serial, 2));
            return;
        }
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration,
                    BootstrapSnapshot.Phase.CHECKING_DEVICE)) return;
            registration = result.value();
            publishLocked(BootstrapSnapshot.state(
                    expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASE_SETTING,
                    BootstrapSnapshot.Endpoint.BASE_SETTING,
                    BootstrapSnapshot.FailureReason.NONE,
                    serialDiagnostic));
        }
        runBaseSetting(expectedGeneration, result.value(), 1);
    }

    private void runBaseSetting(
            long expectedGeneration, DeviceRegistration currentRegistration, int attempt) {
        ApiResult<BaseSettingSnapshot> result = invokeWithWatchdog(
                expectedGeneration,
                BootstrapSnapshot.Phase.LOADING_BASE_SETTING,
                token -> service.baseSetting(currentRegistration, token));
        if (result == null || !advanceIfCurrent(
                expectedGeneration, BootstrapSnapshot.Phase.LOADING_BASE_SETTING)) {
            return;
        }
        if (!result.isSuccess()) {
            handleFailure(
                    expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASE_SETTING,
                    BootstrapSnapshot.Endpoint.BASE_SETTING,
                    result.failure(),
                    attempt,
                    () -> runBaseSetting(expectedGeneration, currentRegistration, 2));
            return;
        }
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASE_SETTING)) return;
            baseSetting = result.value();
            publishLocked(BootstrapSnapshot.state(
                    expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASIC_DATA,
                    BootstrapSnapshot.Endpoint.BASIC_DATA,
                    BootstrapSnapshot.FailureReason.NONE,
                    serialDiagnostic));
        }
        runBasicData(expectedGeneration, currentRegistration, result.value(), 1);
    }

    private void runBasicData(
            long expectedGeneration,
            DeviceRegistration currentRegistration,
            BaseSettingSnapshot currentBaseSetting,
            int attempt) {
        ApiResult<BasicDataSnapshot> result = invokeWithWatchdog(
                expectedGeneration,
                BootstrapSnapshot.Phase.LOADING_BASIC_DATA,
                token -> service.basicData(currentRegistration, token));
        if (result == null || !advanceIfCurrent(
                expectedGeneration, BootstrapSnapshot.Phase.LOADING_BASIC_DATA)) {
            return;
        }
        if (!result.isSuccess()) {
            handleFailure(
                    expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASIC_DATA,
                    BootstrapSnapshot.Endpoint.BASIC_DATA,
                    result.failure(),
                    attempt,
                    () -> runBasicData(
                            expectedGeneration, currentRegistration, currentBaseSetting, 2));
            return;
        }
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration,
                    BootstrapSnapshot.Phase.LOADING_BASIC_DATA)) return;
            BasicDataSnapshot currentBasicData = result.value();
            publishLocked(BootstrapSnapshot.ready(
                    expectedGeneration,
                    serialDiagnostic,
                    currentRegistration,
                    currentBaseSetting,
                    currentBasicData));
        }
    }

    private <T> ApiResult<T> invokeWithWatchdog(
            long expectedGeneration,
            BootstrapSnapshot.Phase expectedPhase,
            ServiceCall<T> call) {
        if (!advanceIfCurrent(expectedGeneration, expectedPhase)) return null;
        CallToken token = new CallToken();
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration, expectedPhase)) return null;
            currentToken = token;
            try {
                Future<?> submitted = scheduler.schedule(
                        () -> timeoutIfCurrent(expectedGeneration, expectedPhase, token),
                        WATCHDOG_MILLIS);
                if (submitted == null) {
                    throw new IllegalStateException("Missing watchdog future");
                }
                watchdogFuture = submitted;
            } catch (RuntimeException rejected) {
                currentToken = null;
                blockLocked(
                        expectedGeneration,
                        endpointFor(expectedPhase),
                        BootstrapSnapshot.FailureReason.CONFIGURATION);
                return null;
            }
        }

        ApiResult<T> result;
        try {
            result = call.execute(token);
        } catch (RuntimeException programmerFailure) {
            result = null;
        } finally {
            synchronized (lock) {
                if (currentToken == token) {
                    currentToken = null;
                    cancelFutureLocked(watchdogFuture);
                    watchdogFuture = null;
                }
            }
        }
        if (!advanceIfCurrent(expectedGeneration, expectedPhase)) return null;
        if (result == null) {
            block(
                    expectedGeneration,
                    expectedPhase,
                    endpointFor(expectedPhase),
                    BootstrapSnapshot.FailureReason.CONFIGURATION);
            return null;
        }
        return result;
    }

    private void handleFailure(
            long expectedGeneration,
            BootstrapSnapshot.Phase expectedPhase,
            BootstrapSnapshot.Endpoint endpoint,
            ServerFailure failure,
            int attempt,
            Runnable retry) {
        if (!advanceIfCurrent(expectedGeneration, expectedPhase)) return;
        if (attempt == 1 && retryable(failure.kind())) {
            synchronized (lock) {
                if (!isCurrentLocked(expectedGeneration, expectedPhase)) return;
                publishLocked(BootstrapSnapshot.state(
                        expectedGeneration,
                        BootstrapSnapshot.Phase.RETRY_WAIT,
                        endpoint,
                        mapFailure(failure.kind()),
                        serialDiagnostic));
                if (!isCurrentLocked(expectedGeneration,
                        BootstrapSnapshot.Phase.RETRY_WAIT)) return;
                try {
                    Future<?> submitted = scheduler.schedule(
                            () -> startRetry(expectedGeneration, endpoint, retry),
                            RETRY_DELAY_MILLIS);
                    if (submitted == null) {
                        throw new IllegalStateException("Missing retry future");
                    }
                    retryFuture = submitted;
                } catch (RuntimeException rejected) {
                    blockLocked(
                            expectedGeneration,
                            endpoint,
                            BootstrapSnapshot.FailureReason.CONFIGURATION);
                }
            }
            return;
        }
        block(expectedGeneration, expectedPhase, endpoint, mapFailure(failure.kind()));
    }

    private void startRetry(
            long expectedGeneration,
            BootstrapSnapshot.Endpoint endpoint,
            Runnable retry) {
        if (!advanceIfCurrent(
                expectedGeneration, BootstrapSnapshot.Phase.RETRY_WAIT)) return;
        synchronized (lock) {
            if (!isCurrentLocked(
                    expectedGeneration, BootstrapSnapshot.Phase.RETRY_WAIT)
                    || snapshot.endpoint() != endpoint) {
                return;
            }
            retryFuture = null;
            publishLocked(BootstrapSnapshot.state(
                    expectedGeneration,
                    phaseFor(endpoint),
                    endpoint,
                    BootstrapSnapshot.FailureReason.NONE,
                    serialDiagnostic));
            try {
                Future<?> submitted = scheduler.submit(retry);
                if (submitted == null) {
                    throw new IllegalStateException("Missing retry worker future");
                }
                workerFuture = submitted;
            } catch (RuntimeException rejected) {
                blockLocked(
                        expectedGeneration,
                        endpoint,
                        BootstrapSnapshot.FailureReason.CONFIGURATION);
            }
        }
    }

    private void timeoutIfCurrent(
            long expectedGeneration,
            BootstrapSnapshot.Phase expectedPhase,
            CallToken token) {
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration, expectedPhase)
                    || currentToken != token) {
                return;
            }
            token.cancel(CallToken.Reason.TIMEOUT);
        }
    }

    private boolean advanceIfCurrent(
            long expectedGeneration, BootstrapSnapshot.Phase expectedPhase) {
        synchronized (lock) {
            return isCurrentLocked(expectedGeneration, expectedPhase);
        }
    }

    private boolean isCurrentLocked(
            long expectedGeneration, BootstrapSnapshot.Phase expectedPhase) {
        return !closed
                && generation == expectedGeneration
                && snapshot.phase() == expectedPhase;
    }

    private void block(
            long expectedGeneration,
            BootstrapSnapshot.Phase expectedPhase,
            BootstrapSnapshot.Endpoint endpoint,
            BootstrapSnapshot.FailureReason reason) {
        synchronized (lock) {
            if (!isCurrentLocked(expectedGeneration, expectedPhase)) return;
            blockLocked(expectedGeneration, endpoint, reason);
        }
    }

    private void blockLocked(
            long expectedGeneration,
            BootstrapSnapshot.Endpoint endpoint,
            BootstrapSnapshot.FailureReason reason) {
        if (generation != expectedGeneration || closed) return;
        registration = null;
        baseSetting = null;
        publishLocked(BootstrapSnapshot.state(
                expectedGeneration,
                BootstrapSnapshot.Phase.BLOCKED,
                endpoint,
                reason,
                serialDiagnostic));
    }

    private void publishLocked(BootstrapSnapshot next) {
        snapshot = next;
        try {
            observer.onSnapshot(next);
        } catch (RuntimeException ignored) {
            // Observer failures cannot change the authoritative bootstrap state.
        }
    }

    private void invalidateOwnedWorkLocked(CallToken.Reason reason) {
        if (currentToken != null) currentToken.cancel(reason);
        currentToken = null;
        cancelFutureLocked(watchdogFuture);
        watchdogFuture = null;
        cancelFutureLocked(retryFuture);
        retryFuture = null;
        cancelFutureLocked(workerFuture);
        workerFuture = null;
    }

    private static void cancelFutureLocked(Future<?> future) {
        if (future != null) future.cancel(true);
    }

    private static boolean retryable(ServerFailure.Kind kind) {
        return kind == ServerFailure.Kind.NETWORK || kind == ServerFailure.Kind.TIMEOUT;
    }

    private static BootstrapSnapshot.FailureReason mapFailure(ServerFailure.Kind kind) {
        switch (kind) {
            case CONFIGURATION: return BootstrapSnapshot.FailureReason.KEY_MISSING;
            case CLOCK_INVALID: return BootstrapSnapshot.FailureReason.CLOCK_INVALID;
            case CANCELLED: return BootstrapSnapshot.FailureReason.CANCELLED;
            case TIMEOUT: return BootstrapSnapshot.FailureReason.TIMEOUT;
            case NETWORK: return BootstrapSnapshot.FailureReason.NETWORK;
            case TLS: return BootstrapSnapshot.FailureReason.TLS;
            case REDIRECT: return BootstrapSnapshot.FailureReason.REDIRECT;
            case HTTP: return BootstrapSnapshot.FailureReason.HTTP;
            case REMOTE_REJECTED: return BootstrapSnapshot.FailureReason.REMOTE_REJECTED;
            case CONTRACT: return BootstrapSnapshot.FailureReason.CONTRACT;
            case RESPONSE_TOO_LARGE:
            case INVALID_UTF8:
            case INVALID_JSON:
                return BootstrapSnapshot.FailureReason.INVALID_RESPONSE;
            default:
                return BootstrapSnapshot.FailureReason.CONTRACT;
        }
    }

    private static BootstrapSnapshot.Endpoint endpointFor(
            BootstrapSnapshot.Phase phase) {
        switch (phase) {
            case CHECKING_DEVICE: return BootstrapSnapshot.Endpoint.CHECK_DEVICE;
            case LOADING_BASE_SETTING: return BootstrapSnapshot.Endpoint.BASE_SETTING;
            case LOADING_BASIC_DATA: return BootstrapSnapshot.Endpoint.BASIC_DATA;
            default: return BootstrapSnapshot.Endpoint.NONE;
        }
    }

    private static BootstrapSnapshot.Phase phaseFor(
            BootstrapSnapshot.Endpoint endpoint) {
        switch (endpoint) {
            case CHECK_DEVICE: return BootstrapSnapshot.Phase.CHECKING_DEVICE;
            case BASE_SETTING: return BootstrapSnapshot.Phase.LOADING_BASE_SETTING;
            case BASIC_DATA: return BootstrapSnapshot.Phase.LOADING_BASIC_DATA;
            default: throw new IllegalArgumentException("Invalid retry endpoint");
        }
    }

    private interface ServiceCall<T> {
        ApiResult<T> execute(CallToken token);
    }
}
