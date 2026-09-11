package com.codex.lockertest.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated bootstrap headers with an exact, immutable name allowlist. */
public final class BootstrapHeaders {
    public static final String MERCHANT_AUTH = "gmt-merchant-auth";
    public static final String DEVICE_NO = "device-no";

    private final Map<String, String> values;

    private BootstrapHeaders(Map<String, String> values) {
        this.values = values;
    }

    public static BootstrapHeaders none() {
        return new BootstrapHeaders(Collections.emptyMap());
    }

    public static BootstrapHeaders bound(String merchantAuth, String deviceNo) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MERCHANT_AUTH, merchantAuth);
        values.put(DEVICE_NO, deviceNo);
        return from(values);
    }

    public static BootstrapHeaders from(Map<String, String> source) {
        if (source == null) throw invalidHeaders();
        if (source.isEmpty()) return none();
        if (source.size() != 2
                || !source.containsKey(MERCHANT_AUTH)
                || !source.containsKey(DEVICE_NO)) {
            throw invalidHeaders();
        }
        String merchantAuth = source.get(MERCHANT_AUTH);
        String deviceNo = source.get(DEVICE_NO);
        validateValue(merchantAuth);
        validateValue(deviceNo);
        Map<String, String> copy = new LinkedHashMap<>();
        copy.put(MERCHANT_AUTH, merchantAuth);
        copy.put(DEVICE_NO, deviceNo);
        return new BootstrapHeaders(Collections.unmodifiableMap(copy));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<String, String> asMap() {
        return values;
    }

    private static void validateValue(String value) {
        if (value == null || value.length() < 1 || value.length() > 256) {
            throw invalidHeaders();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) throw invalidHeaders();
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if (Character.isWhitespace(first) || Character.isSpaceChar(first)
                || Character.isWhitespace(last) || Character.isSpaceChar(last)) {
            throw invalidHeaders();
        }
    }

    private static IllegalArgumentException invalidHeaders() {
        return new IllegalArgumentException("Invalid bootstrap headers");
    }
}
