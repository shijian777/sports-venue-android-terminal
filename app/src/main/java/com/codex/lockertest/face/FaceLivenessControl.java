package com.codex.lockertest.face;

/**
 * Keeps optional RGB liveness independent from the ordinary face-capture path.
 * A persisted administrator choice becomes effective only after the SDK proves
 * that this installation supports the liveness model.
 */
public final class FaceLivenessControl {
    public enum Capability {
        WAITING_FOR_LICENSE,
        PROBING,
        SUPPORTED,
        UNSUPPORTED,
        FAILED
    }

    public enum Mode {
        ORDINARY_CAPTURE,
        RGB_LIVENESS
    }

    public interface BooleanStore {
        boolean read();
        boolean write(boolean value);
    }

    public static final class Snapshot {
        private final Capability capability;
        private final boolean switchEnabled;
        private final boolean requestedEnabled;
        private final Mode mode;

        private Snapshot(Capability capability, boolean switchEnabled,
                boolean requestedEnabled, Mode mode) {
            this.capability = capability;
            this.switchEnabled = switchEnabled;
            this.requestedEnabled = requestedEnabled;
            this.mode = mode;
        }

        public Capability capability() { return capability; }
        public boolean switchEnabled() { return switchEnabled; }
        public boolean requestedEnabled() { return requestedEnabled; }
        public Mode mode() { return mode; }
    }

    private final BooleanStore store;
    private Capability capability = Capability.WAITING_FOR_LICENSE;
    private boolean requestedEnabled;

    public FaceLivenessControl(BooleanStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store cannot be null");
        }
        this.store = store;
        try {
            requestedEnabled = store.read();
        } catch (RuntimeException ignored) {
            requestedEnabled = false;
        }
    }

    public synchronized void onProbeStarted() {
        capability = Capability.PROBING;
    }

    public synchronized void onProbeSupported() {
        capability = Capability.SUPPORTED;
    }

    public synchronized void onProbeUnsupported() {
        capability = Capability.UNSUPPORTED;
        clearRequestedPreference();
    }

    public synchronized void onProbeFailed() {
        capability = Capability.FAILED;
        clearRequestedPreference();
    }

    public synchronized void onLicenseUnavailable() {
        capability = Capability.WAITING_FOR_LICENSE;
        clearRequestedPreference();
    }

    /** Returns the authoritative enabled value after applying capability rules. */
    public synchronized boolean setRequestedEnabled(boolean enabled) {
        boolean accepted = enabled && capability == Capability.SUPPORTED;
        requestedEnabled = accepted;
        if (!persist(accepted) && accepted) requestedEnabled = false;
        return requestedEnabled;
    }

    public synchronized Snapshot snapshot() {
        boolean supported = capability == Capability.SUPPORTED;
        boolean effective = supported && requestedEnabled;
        return new Snapshot(capability, supported, effective,
                effective ? Mode.RGB_LIVENESS : Mode.ORDINARY_CAPTURE);
    }

    private void clearRequestedPreference() {
        requestedEnabled = false;
        persist(false);
    }

    private boolean persist(boolean value) {
        try {
            return store.write(value);
        } catch (RuntimeException ignored) {
            if (value) {
                requestedEnabled = false;
            }
            return false;
        }
    }
}
