package com.codex.lockertest.protocol;

public final class HexCodec {
    private HexCodec() {
    }

    public static byte[] decode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("指令不能为空");
        }

        String normalized = input.replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("指令不能为空");
        }
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制指令长度必须为偶数");
        }

        byte[] result = new byte[normalized.length() / 2];
        for (int index = 0; index < normalized.length(); index += 2) {
            int high = Character.digit(normalized.charAt(index), 16);
            int low = Character.digit(normalized.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("指令只能包含 0-9、A-F");
            }
            result[index / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public static String format(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder(bytes.length * 3 - 1);
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", bytes[index] & 0xFF));
        }
        return builder.toString();
    }
}
