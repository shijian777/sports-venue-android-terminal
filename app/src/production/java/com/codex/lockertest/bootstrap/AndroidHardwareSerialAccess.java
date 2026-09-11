package com.codex.lockertest.bootstrap;

import android.os.Build;

public final class AndroidHardwareSerialAccess implements HardwareSerialAccess {
    @Override
    public int sdkInt() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public String api26Serial() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Build.getSerial();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String legacySerial() {
        return Build.SERIAL;
    }

    @Override
    public String systemProperty(String name) {
        if (name == null) return null;
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class)
                    .invoke(null, name);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
