package com.codex.lockertest.business;

public final class InstallerSession {
    private final SessionToken token;

    public InstallerSession(SessionToken token) {
        if (token == null) throw new IllegalArgumentException("Installer token is required");
        this.token = token;
    }

    public SessionToken token() { return token; }
}
