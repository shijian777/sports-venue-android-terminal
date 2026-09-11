package com.codex.lockertest.bootstrap;

public final class Rk3288SerialSelector {
    private static final int API_26 = 26;
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;
    private static final String DEFAULT_UNAVAILABLE = "********";

    public DeviceSerial select(HardwareSerialAccess access) {
        if (access == null) throw new IllegalArgumentException("Missing serial access");

        String[] firstInvalidDiagnostic = new String[1];
        DeviceSerial selected;
        if (safeSdkInt(access) >= API_26) {
            selected = selectCandidate(safeApi26Serial(access), firstInvalidDiagnostic);
            if (selected != null) return selected;
        }

        selected = selectCandidate(safeLegacySerial(access), firstInvalidDiagnostic);
        if (selected != null) return selected;

        selected = selectCandidate(
                safeSystemProperty(access, "ro.serialno"), firstInvalidDiagnostic);
        if (selected != null) return selected;

        selected = selectCandidate(
                safeSystemProperty(access, "ro.boot.serialno"), firstInvalidDiagnostic);
        if (selected != null) return selected;

        return DeviceSerial.unavailable(firstInvalidDiagnostic[0] == null
                ? DEFAULT_UNAVAILABLE : firstInvalidDiagnostic[0]);
    }

    private static DeviceSerial selectCandidate(
            String candidate, String[] firstInvalidDiagnostic) {
        if (candidate == null || candidate.length() == 0) return null;
        if (valid(candidate)) {
            return DeviceSerial.available(candidate, maskExceptFinalFour(candidate));
        }
        if (firstInvalidDiagnostic[0] == null) {
            firstInvalidDiagnostic[0] = maskAll(candidate);
            if (firstInvalidDiagnostic[0].length() == 0) {
                firstInvalidDiagnostic[0] = DEFAULT_UNAVAILABLE;
            }
        }
        return null;
    }

    private static boolean valid(String candidate) {
        int length = candidate.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH
                || "unknown".equalsIgnoreCase(candidate)) {
            return false;
        }
        boolean nonSpace = false;
        for (int index = 0; index < length; index++) {
            char value = candidate.charAt(index);
            if (value < 0x20 || value > 0x7e) return false;
            if (value != ' ') nonSpace = true;
        }
        return nonSpace;
    }

    private static String maskExceptFinalFour(String value) {
        return repeat('*', value.length() - 4) + value.substring(value.length() - 4);
    }

    private static String maskAll(String value) {
        return repeat('*', value.length());
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }

    private static int safeSdkInt(HardwareSerialAccess access) {
        try {
            return access.sdkInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String safeApi26Serial(HardwareSerialAccess access) {
        try {
            return access.api26Serial();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeLegacySerial(HardwareSerialAccess access) {
        try {
            return access.legacySerial();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeSystemProperty(HardwareSerialAccess access, String name) {
        try {
            return access.systemProperty(name);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
