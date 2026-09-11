package com.codex.lockertest.serial;

import java.io.File;
import java.util.Arrays;

public final class SerialDiagnostics {
    private SerialDiagnostics() {
    }

    public static String basic(File device, String[] supportedAbis) {
        String path = device == null ? "null" : device.getAbsolutePath();
        boolean exists = device != null && device.exists();
        boolean readable = device != null && device.canRead();
        boolean writable = device != null && device.canWrite();
        String[] abis = supportedAbis == null ? new String[0] : supportedAbis;
        return "path=" + path
                + ", exists=" + exists
                + ", read=" + readable
                + ", write=" + writable
                + ", ABI=" + Arrays.toString(abis);
    }

    public static String formatRawOpenFailure(String function, int errno, String errnoName) {
        String safeFunction = function == null || function.trim().isEmpty()
                ? "open" : function.trim();
        String safeName = errnoName == null || errnoName.trim().isEmpty()
                ? "UNKNOWN" : errnoName.trim();
        return "raw-open=" + safeFunction + ": errno=" + errno + " (" + safeName + ")";
    }

}
