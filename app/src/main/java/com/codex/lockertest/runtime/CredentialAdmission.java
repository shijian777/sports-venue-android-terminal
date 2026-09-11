package com.codex.lockertest.runtime;

import com.codex.lockertest.model.UnlockMethod;

/** Immutable decision for admitting a credential without retaining that credential. */
public final class CredentialAdmission {
    private final boolean accepted;
    private final UnlockMethod method;
    private final String message;

    private CredentialAdmission(boolean accepted, UnlockMethod method, String message) {
        this.accepted = accepted;
        this.method = method;
        this.message = message;
    }

    public static CredentialAdmission accepted(UnlockMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("Unlock method is required");
        }
        return new CredentialAdmission(true, method, null);
    }

    public static CredentialAdmission rejected(String message) {
        requireMessage(message);
        return new CredentialAdmission(false, null, message);
    }

    public boolean accepted() {
        return accepted;
    }

    public UnlockMethod method() {
        return method;
    }

    public String message() {
        return message;
    }

    private static void requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Rejection message is required");
        }
    }
}
