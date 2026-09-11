package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.UnlockMethod;

/** Immutable proof presented to begin a return journey. */
public final class ReturnIdentity {
    private final UnlockMethod method;
    private final String credential;
    private final long issuedAt;

    public ReturnIdentity(UnlockMethod method, String credential, long issuedAt) {
        if (method == null) {
            throw new IllegalArgumentException("Unlock method is required");
        }
        if (credential == null || credential.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential is required");
        }
        if (issuedAt < 0L) {
            throw new IllegalArgumentException("Issue time must not be negative");
        }
        this.method = method;
        this.credential = credential;
        this.issuedAt = issuedAt;
    }

    public UnlockMethod method() {
        return method;
    }

    public String credential() {
        return credential;
    }

    public long issuedAt() {
        return issuedAt;
    }

    @Override
    public String toString() {
        return "ReturnIdentity{method=" + method + ", issuedAt=" + issuedAt + "}";
    }
}
