package com.codex.lockertest.face.verification;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FaceVerificationEnvironment {
    interface EnabledStateStore {
        boolean load();
        void save(boolean enabled);
    }

    interface TicketIdGenerator {
        String nextTicketId();
    }

    interface DisableListener {
        void onDisabled();
    }

    static final class IssuerCapability {
        private IssuerCapability() {
        }
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Object lock = new Object();
    private final FaceVerificationSource acceptedSource;
    private final boolean enablingAllowed;
    private final EnabledStateStore stateStore;
    private final TicketIdGenerator ticketIds;
    private final IssuerCapability issuerCapability;
    private final FaceVerificationTicketRegistry registry =
            new FaceVerificationTicketRegistry();
    private final Set<DisableListener> disableListeners =
            new LinkedHashSet<DisableListener>();
    private boolean enabled;
    private long policyEpoch;

    FaceVerificationEnvironment(FaceVerificationSource acceptedSource,
            boolean enablingAllowed, EnabledStateStore stateStore,
            TicketIdGenerator ticketIds, IssuerCapability issuerCapability) {
        if (acceptedSource == null || stateStore == null || ticketIds == null
                || issuerCapability == null) {
            throw new IllegalArgumentException("environment dependencies cannot be null");
        }
        this.acceptedSource = acceptedSource;
        this.enablingAllowed = enablingAllowed;
        this.stateStore = stateStore;
        this.ticketIds = ticketIds;
        this.issuerCapability = issuerCapability;
        this.enabled = enablingAllowed && stateStore.load();
    }

    public boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }

    public long currentPolicyEpoch() {
        synchronized (lock) {
            return policyEpoch;
        }
    }

    public boolean setEnabled(boolean requestedEnabled) {
        List<DisableListener> notify = null;
        boolean result;
        synchronized (lock) {
            if (requestedEnabled && !enablingAllowed) {
                return false;
            }
            if (enabled == requestedEnabled) {
                return enabled;
            }
            stateStore.save(requestedEnabled);
            enabled = requestedEnabled;
            policyEpoch++;
            if (!enabled) {
                registry.revokeAll();
                notify = new ArrayList<DisableListener>(disableListeners);
                disableListeners.clear();
            }
            result = enabled;
        }
        if (notify != null) {
            for (DisableListener listener : notify) {
                try {
                    listener.onDisabled();
                } catch (RuntimeException ignored) {
                    // One client cannot prevent the remaining disable fanout.
                }
            }
        }
        return result;
    }

    public FaceVerificationTicketValidator.TicketVerdict validateTicket(
            FaceVerificationResult result, String expectedRequestId,
            String expectedDeviceBinding, String expectedProcessBinding,
            long nowEpochMillis) {
        synchronized (lock) {
            return FaceVerificationTicketValidator.validate(result, enabled,
                    acceptedSource, expectedRequestId, expectedDeviceBinding,
                    expectedProcessBinding, policyEpoch, registry, nowEpochMillis);
        }
    }

    FaceVerificationResult issuePassed(IssuerCapability capability,
            String requestId, String credential,
            long expiresAtEpochMillis, String deviceBinding, String processBinding,
            long expectedPolicyEpoch, long issuedAtEpochMillis) {
        synchronized (lock) {
            if (capability != issuerCapability) {
                throw new SecurityException("issuer capability does not match environment");
            }
            if (!enabled || policyEpoch != expectedPolicyEpoch) {
                throw new IllegalStateException("verification policy changed before issue");
            }
            if (issuedAtEpochMillis >= expiresAtEpochMillis) {
                throw new IllegalArgumentException("issued ticket must expire in the future");
            }
            String ticketId = nextUniqueTicketId(issuedAtEpochMillis);
            FaceVerificationResult result = FaceVerificationResult.passed(
                    acceptedSource, requestId, credential, ticketId,
                    expiresAtEpochMillis, deviceBinding, processBinding, policyEpoch);
            registry.registerIssued(result, issuedAtEpochMillis);
            return result;
        }
    }

    boolean addDisableListener(DisableListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (lock) {
            if (!enabled) {
                return false;
            }
            disableListeners.add(listener);
            return true;
        }
    }

    void removeDisableListener(DisableListener listener) {
        synchronized (lock) {
            disableListeners.remove(listener);
        }
    }

    static String secureTicketId() {
        byte[] random = new byte[16];
        SECURE_RANDOM.nextBytes(random);
        StringBuilder value = new StringBuilder(32);
        for (byte item : random) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    static IssuerCapability newIssuerCapability() {
        return new IssuerCapability();
    }

    private String nextUniqueTicketId(long nowEpochMillis) {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = ticketIds.nextTicketId();
            if (candidate != null && candidate.matches("[0-9a-f]{32}")
                    && !registry.containsTicketId(candidate, nowEpochMillis)) {
                return candidate;
            }
        }
        throw new IllegalStateException("unable to allocate unique 128-bit ticket ID");
    }
}
