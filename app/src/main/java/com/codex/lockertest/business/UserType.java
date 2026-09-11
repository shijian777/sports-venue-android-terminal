package com.codex.lockertest.business;

public enum UserType {
    USER(0), ADMIN(1);

    private final int wireValue;

    UserType(int wireValue) { this.wireValue = wireValue; }

    public int wireValue() { return wireValue; }

    static UserType fromWire(int value) {
        if (value == 0) return USER;
        if (value == 1) return ADMIN;
        throw new IllegalArgumentException("Unknown user type");
    }
}
