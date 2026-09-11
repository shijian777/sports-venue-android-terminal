package com.codex.lockertest.business;

/** Validated opaque server token with a deliberately redacted string representation. */
public final class SessionToken {
    private final String value;

    private SessionToken(String value) {
        this.value = BusinessValues.text(value, "Session token", 4096);
    }

    public static SessionToken of(String value) {
        return new SessionToken(value);
    }

    public String value() { return value; }

    @Override public String toString() { return "<redacted-session-token>"; }
}
