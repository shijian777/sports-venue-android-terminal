package com.codex.lockertest.ui;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

public final class UnlockPageModel {
    public static final String DEVICE_UNAVAILABLE = "设备暂未接入";

    private final UnlockMethod method;
    private final FeatureAvailability availability;

    public UnlockPageModel(UnlockMethod method, FeatureAvailability availability) {
        if (method == null) {
            throw new IllegalArgumentException("method cannot be null");
        }
        if (availability == null) {
            throw new IllegalArgumentException("availability cannot be null");
        }
        this.method = method;
        this.availability = availability;
    }

    public UnlockMethod method() {
        return method;
    }

    public boolean isAvailable() {
        return availability.isEnabled(method);
    }

    public boolean usesNumericKeypad() {
        return method == UnlockMethod.PHONE || method == UnlockMethod.PASSWORD;
    }

    public int maxInputLength() {
        if (method == UnlockMethod.PHONE) {
            return 11;
        }
        if (method == UnlockMethod.PASSWORD) {
            return 6;
        }
        return 0;
    }

    public boolean isMasked() {
        return method == UnlockMethod.PASSWORD;
    }

    public boolean canSubmit(String payload) {
        if (!isAvailable()) {
            return false;
        }
        if (!usesNumericKeypad()) {
            return true;
        }
        return payload != null
                && payload.length() == maxInputLength()
                && containsAsciiDigitsOnly(payload);
    }

    public String unavailableMessage() {
        return DEVICE_UNAVAILABLE;
    }

    public String recognitionActionLabel() {
        return method == UnlockMethod.QR ? "开始扫码" : "开始识别";
    }

    public String recognitionProgressLabel() {
        return method == UnlockMethod.QR ? "正在扫码..." : "正在识别...";
    }

    private static boolean containsAsciiDigitsOnly(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
