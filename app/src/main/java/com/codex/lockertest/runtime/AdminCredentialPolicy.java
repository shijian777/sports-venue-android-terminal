package com.codex.lockertest.runtime;

public interface AdminCredentialPolicy {
    int requiredLength();
    boolean matches(char[] candidate);
    String unavailableMessage();
}
