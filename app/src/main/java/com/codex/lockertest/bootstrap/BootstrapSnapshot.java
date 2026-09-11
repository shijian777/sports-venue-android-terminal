package com.codex.lockertest.bootstrap;

/** Immutable, payload-minimal state published by the bootstrap coordinator. */
public final class BootstrapSnapshot {
    public enum Phase {
        IDLE,
        READING_SERIAL,
        CHECKING_DEVICE,
        LOADING_BASE_SETTING,
        LOADING_BASIC_DATA,
        RETRY_WAIT,
        READY_READ_ONLY,
        BLOCKED,
        CANCELLED,
        CLOSED
    }

    public enum Endpoint {
        NONE,
        CHECK_DEVICE,
        BASE_SETTING,
        BASIC_DATA
    }

    public enum FailureReason {
        NONE,
        CONFIGURATION,
        KEY_MISSING,
        SERIAL_UNAVAILABLE,
        CLOCK_INVALID,
        CANCELLED,
        TIMEOUT,
        NETWORK,
        TLS,
        REDIRECT,
        HTTP,
        REMOTE_REJECTED,
        INVALID_RESPONSE,
        CONTRACT
    }

    private final long generation;
    private final Phase phase;
    private final Endpoint endpoint;
    private final FailureReason failureReason;
    private final String serialDiagnostic;
    private final DeviceRegistration registration;
    private final BaseSettingSnapshot baseSetting;
    private final BasicDataSnapshot basicData;

    private BootstrapSnapshot(
            long generation,
            Phase phase,
            Endpoint endpoint,
            FailureReason failureReason,
            String serialDiagnostic,
            DeviceRegistration registration,
            BaseSettingSnapshot baseSetting,
            BasicDataSnapshot basicData) {
        if (generation < 0L || phase == null || endpoint == null
                || failureReason == null || serialDiagnostic == null) {
            throw new IllegalArgumentException("Invalid bootstrap snapshot");
        }
        boolean ready = phase == Phase.READY_READ_ONLY;
        if (ready != (registration != null && baseSetting != null && basicData != null)) {
            throw new IllegalArgumentException("Invalid bootstrap snapshot");
        }
        if (ready && (endpoint != Endpoint.NONE || failureReason != FailureReason.NONE)) {
            throw new IllegalArgumentException("Invalid bootstrap snapshot");
        }
        if (!ready && (registration != null || baseSetting != null || basicData != null)) {
            throw new IllegalArgumentException("Invalid bootstrap snapshot");
        }
        this.generation = generation;
        this.phase = phase;
        this.endpoint = endpoint;
        this.failureReason = failureReason;
        this.serialDiagnostic = serialDiagnostic;
        this.registration = registration;
        this.baseSetting = baseSetting;
        this.basicData = basicData;
    }

    static BootstrapSnapshot state(
            long generation,
            Phase phase,
            Endpoint endpoint,
            FailureReason failureReason,
            String serialDiagnostic) {
        return new BootstrapSnapshot(
                generation, phase, endpoint, failureReason, serialDiagnostic,
                null, null, null);
    }

    static BootstrapSnapshot ready(
            long generation,
            String serialDiagnostic,
            DeviceRegistration registration,
            BaseSettingSnapshot baseSetting,
            BasicDataSnapshot basicData) {
        return new BootstrapSnapshot(
                generation, Phase.READY_READ_ONLY, Endpoint.NONE, FailureReason.NONE,
                serialDiagnostic, registration, baseSetting, basicData);
    }

    public long generation() { return generation; }
    public Phase phase() { return phase; }
    public Endpoint endpoint() { return endpoint; }
    public FailureReason failureReason() { return failureReason; }
    public String serialDiagnostic() { return serialDiagnostic; }
    public DeviceRegistration registration() { return registration; }
    public BaseSettingSnapshot baseSetting() { return baseSetting; }
    public BasicDataSnapshot basicData() { return basicData; }
}
