package com.codex.lockertest.bootstrap;

public final class BasicDataSnapshot {
    private final int totalLockers;
    private final int availableLockers;
    private final String dynamicVerificationCode;

    BasicDataSnapshot(int totalLockers, int availableLockers,
            String dynamicVerificationCode) {
        if (dynamicVerificationCode == null) {
            throw new IllegalArgumentException("Missing dynamic verification code");
        }
        this.totalLockers = totalLockers;
        this.availableLockers = availableLockers;
        this.dynamicVerificationCode = dynamicVerificationCode;
    }

    public int totalLockers() {
        return totalLockers;
    }

    public int availableLockers() {
        return availableLockers;
    }

    public String dynamicVerificationCode() {
        return dynamicVerificationCode;
    }
}
