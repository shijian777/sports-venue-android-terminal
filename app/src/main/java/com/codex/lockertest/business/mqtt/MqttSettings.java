package com.codex.lockertest.business.mqtt;

/** Immutable, validated MQTT connection settings. */
public final class MqttSettings {
    private final String host;
    private final int port;
    private final String clientId;
    private final String username;
    private final String password;
    private final String topic;

    public MqttSettings(String host, int port, String clientId,
                        String username, String password, String topic) {
        requireHost(host);
        requirePort(port);
        requireOpaque(clientId, 1, 128);
        requireOpaque(username, 0, 256);
        requireOpaque(password, 0, 4096);
        requireOpaque(topic, 1, 512);
        if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            invalid();
        }
        if (!password.isEmpty() && username.isEmpty()) {
            invalid();
        }
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.topic = topic;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String clientId() {
        return clientId;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String topic() {
        return topic;
    }

    private static void requireHost(String value) {
        if (value == null || value.isEmpty() || value.length() > 253) {
            invalid();
        }
        boolean digitsAndDots = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7f || Character.isWhitespace(c)) {
                invalid();
            }
            if (!((c >= '0' && c <= '9') || c == '.')) {
                digitsAndDots = false;
            }
        }
        if (digitsAndDots) {
            requireIpv4(value);
            return;
        }
        int labelStart = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '.') {
                int length = i - labelStart;
                if (length < 1 || length > 63
                        || value.charAt(labelStart) == '-'
                        || value.charAt(i - 1) == '-') {
                    invalid();
                }
                labelStart = i + 1;
            } else {
                char c = value.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-')) {
                    invalid();
                }
            }
        }
    }

    private static void requireIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            invalid();
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                invalid();
            }
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                number = number * 10 + (part.charAt(i) - '0');
            }
            if (number > 255) {
                invalid();
            }
        }
    }

    private static void requirePort(int value) {
        if (value < 1 || value > 65535) {
            invalid();
        }
    }

    private static void requireOpaque(String value, int minimumBytes, int maximumBytes) {
        if (value == null) {
            invalid();
        }
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                invalid();
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    invalid();
                }
                i++;
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                invalid();
            } else if (c <= 0x7f) {
                bytes++;
            } else if (c <= 0x7ff) {
                bytes += 2;
            } else {
                bytes += 3;
            }
            if (bytes > maximumBytes) {
                invalid();
            }
        }
        if (bytes < minimumBytes) {
            invalid();
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid MQTT settings");
    }
}
