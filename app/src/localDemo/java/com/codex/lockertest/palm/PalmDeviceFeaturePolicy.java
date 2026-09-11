package com.codex.lockertest.palm;

import java.util.Arrays;

final class PalmDeviceFeaturePolicy {
    static final int DEVICE_FEATURE_BYTES = 560;

    enum Decision { SUCCESS, WAIT, FAILURE }

    private PalmDeviceFeaturePolicy() {}

    static boolean isValid(byte[] feature) {
        if (feature == null || feature.length != DEVICE_FEATURE_BYTES) return false;
        int combined = 0;
        for (byte value : feature) combined |= value;
        return combined != 0;
    }

    static byte[] copyValidated(byte[] feature) {
        return isValid(feature) ? Arrays.copyOf(feature, feature.length) : null;
    }

    static Decision classify(int code, byte[] feature) {
        if (code == 0) return isValid(feature) ? Decision.SUCCESS : Decision.FAILURE;
        if (code == 102 || isRetryableCaptureCode(code)) return Decision.WAIT;
        return Decision.FAILURE;
    }

    private static boolean isRetryableCaptureCode(int code) {
        switch (code) {
            case 301:
            case 302:
            case 303:
            case 351:
            case 352:
            case 353:
            case 354:
            case 355:
            case 358:
            case 359:
                return true;
            default:
                return false;
        }
    }
}
