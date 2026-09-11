package com.codex.lockertest.server;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe first-reason cancellation token with race-safe hooks. */
public final class CallToken {
    public enum Reason {
        NONE,
        CANCELLED,
        TIMEOUT
    }

    public interface Registration {
        void unregister();
    }

    private final Object lock = new Object();
    private final List<HookRegistration> registrations = new ArrayList<>();
    private volatile Reason reason = Reason.NONE;

    public Reason reason() {
        return reason;
    }

    public boolean isCancelled() {
        return reason != Reason.NONE;
    }

    public boolean cancel(Reason requestedReason) {
        requireCancellationReason(requestedReason);
        List<HookRegistration> claimed;
        synchronized (lock) {
            if (reason != Reason.NONE) return false;
            reason = requestedReason;
            claimed = new ArrayList<>(registrations);
            registrations.clear();
            for (HookRegistration registration : claimed) {
                registration.claimed = true;
            }
        }
        for (HookRegistration registration : claimed) {
            registration.invokeClaimed();
        }
        return true;
    }

    public Registration onCancel(Runnable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Cancellation callback is required");
        }
        HookRegistration registration = new HookRegistration(callback);
        boolean invokeImmediately;
        synchronized (lock) {
            invokeImmediately = reason != Reason.NONE;
            if (invokeImmediately) {
                registration.claimed = true;
            } else {
                registrations.add(registration);
            }
        }
        if (invokeImmediately) registration.invokeClaimed();
        return registration;
    }

    private static void requireCancellationReason(Reason requestedReason) {
        if (requestedReason == null || requestedReason == Reason.NONE) {
            throw new IllegalArgumentException("A terminal cancellation reason is required");
        }
    }

    private final class HookRegistration implements Registration {
        private final Runnable callback;
        private boolean claimed;
        private boolean invoked;

        private HookRegistration(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void unregister() {
            synchronized (lock) {
                if (claimed || invoked) return;
                registrations.remove(this);
                invoked = true;
            }
        }

        private void invokeClaimed() {
            synchronized (lock) {
                if (!claimed || invoked) return;
                invoked = true;
            }
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // Cancellation remains authoritative even if cleanup rejects the callback.
            }
        }
    }
}
