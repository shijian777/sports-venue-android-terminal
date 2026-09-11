package com.codex.lockertest.bootstrap;

public final class DeviceSerial {
    private final String value;
    private final String diagnostic;

    private DeviceSerial(String value, String diagnostic) {
        if (diagnostic == null || diagnostic.length() == 0) {
            throw new IllegalArgumentException("Missing serial diagnostic");
        }
        this.value = value;
        this.diagnostic = diagnostic;
    }

    static DeviceSerial available(String value, String diagnostic) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("Missing device serial");
        }
        return new DeviceSerial(value, diagnostic);
    }

    static DeviceSerial unavailable(String diagnostic) {
        return new DeviceSerial(null, diagnostic);
    }

    public boolean isAvailable() {
        return value != null;
    }

    public String value() {
        return value;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
