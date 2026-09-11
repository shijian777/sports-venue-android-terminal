package com.codex.lockertest.bootstrap;

public interface HardwareSerialAccess {
    int sdkInt();

    String api26Serial();

    String legacySerial();

    String systemProperty(String name);
}
