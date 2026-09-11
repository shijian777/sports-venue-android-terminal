package com.codex.lockertest.bootstrap;

public final class Rk3288DeviceSerialProvider implements DeviceSerialProvider {
    private final HardwareSerialAccess access;
    private final Rk3288SerialSelector selector;

    public Rk3288DeviceSerialProvider() {
        this(new AndroidHardwareSerialAccess());
    }

    Rk3288DeviceSerialProvider(HardwareSerialAccess access) {
        if (access == null) throw new IllegalArgumentException("Missing serial access");
        this.access = access;
        this.selector = new Rk3288SerialSelector();
    }

    @Override
    public DeviceSerial read() {
        return selector.select(access);
    }
}
