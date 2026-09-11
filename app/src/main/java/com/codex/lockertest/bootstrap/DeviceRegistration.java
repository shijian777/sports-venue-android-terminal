package com.codex.lockertest.bootstrap;

public final class DeviceRegistration {
    private final String merchantCode;
    private final String deviceNo;

    DeviceRegistration(String merchantCode, String deviceNo) {
        if (merchantCode == null || deviceNo == null) {
            throw new IllegalArgumentException("Missing device registration");
        }
        this.merchantCode = merchantCode;
        this.deviceNo = deviceNo;
    }

    public String merchantCode() {
        return merchantCode;
    }

    public String deviceNo() {
        return deviceNo;
    }
}
