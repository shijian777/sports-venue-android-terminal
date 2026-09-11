package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.JsonNumber;

import java.util.Map;

public final class CheckDeviceResponseParser
        extends CentralControlResponseParser<DeviceRegistration> {
    private static final int MAX_HEADER_VALUE_LENGTH = 256;

    @Override
    protected DeviceRegistration parseSuccessData(Map<String, Object> data) {
        exactKeys(data, "merchant_code", "device_no");
        return new DeviceRegistration(
                text(data.get("merchant_code"), MAX_HEADER_VALUE_LENGTH, false),
                deviceNumber(data.get("device_no")));
    }

    private static String deviceNumber(Object value) {
        if (value instanceof String) {
            return text(value, MAX_HEADER_VALUE_LENGTH, false);
        }
        if (!(value instanceof JsonNumber)) throw contract();
        String token = ((JsonNumber) value).rawToken();
        if (token.length() == 0 || token.length() > MAX_HEADER_VALUE_LENGTH) {
            throw contract();
        }
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < '0' || character > '9') throw contract();
        }
        return token;
    }
}
