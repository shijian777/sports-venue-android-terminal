package com.codex.lockertest.server;

/** Separates string-data signing semantics from exact raw-JSON semantics. */
public final class CentralControlData {
    private final String signingText;
    private final String wireJson;

    private CentralControlData(String signingText, String wireJson) {
        this.signingText = signingText;
        this.wireJson = wireJson;
    }

    public static CentralControlData fromString(String value) {
        if (value == null) throw invalidData();
        return new CentralControlData(value, quoteJsonString(value));
    }

    public static CentralControlData fromRawJson(String rawJson) {
        StrictJson.parse(rawJson);
        return new CentralControlData(rawJson, rawJson);
    }

    public String signingText() {
        return signingText;
    }

    public String wireJson() {
        return wireJson;
    }

    static String quoteJsonString(String value) {
        if (value == null) throw invalidData();
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        appendUnicodeEscape(result, character);
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw invalidData();
                        }
                        result.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw invalidData();
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static void appendUnicodeEscape(StringBuilder result, char character) {
        result.append("\\u00");
        int high = (character >>> 4) & 0x0f;
        int low = character & 0x0f;
        result.append(hexDigit(high)).append(hexDigit(low));
    }

    private static char hexDigit(int value) {
        return (char) (value < 10 ? '0' + value : 'a' + value - 10);
    }

    private static IllegalArgumentException invalidData() {
        return new IllegalArgumentException("Invalid central-control data");
    }
}
