package com.codex.lockertest.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Stateless implementation of the central-control MD5/SHA-1 signing contract. */
public final class CentralControlSigner {
    private CentralControlSigner() { }

    public static Result sign(
            CentralControlData data, ProtocolTimestamp timestamp, char[] sysCode) {
        if (data == null || timestamp == null || sysCode == null || sysCode.length == 0) {
            throw invalidInput();
        }

        char[] keyCopy = Arrays.copyOf(sysCode, sysCode.length);
        byte[] keyBytes = null;
        byte[] digestBytes = null;
        try {
            keyBytes = encodeUtf8(keyCopy);
            String seconds = Long.toString(timestamp.epochSeconds());
            String scode = digestUtf8("MD5", data.signingText());
            String fullDateSha1 = digestUtf8("SHA-1", timestamp.date().fullYear());
            String shortDateSha1 = digestUtf8("SHA-1", timestamp.date().shortYear());

            MessageDigest middle = newDigest("MD5");
            updateUtf8(middle, fullDateSha1);
            updateUtf8(middle, seconds);
            digestBytes = middle.digest();
            String middleMd5 = toHex(digestBytes);
            clear(digestBytes);
            digestBytes = null;

            MessageDigest outer = newDigest("MD5");
            updateUtf8(outer, scode);
            outer.update(keyBytes);
            updateUtf8(outer, seconds);
            updateUtf8(outer, middleMd5);
            updateUtf8(outer, shortDateSha1);
            digestBytes = outer.digest();
            return new Result(scode, toHex(digestBytes));
        } finally {
            clear(digestBytes);
            clear(keyBytes);
            clear(keyCopy);
        }
    }

    private static String digestUtf8(String algorithm, String value) {
        byte[] valueBytes = null;
        byte[] digestBytes = null;
        try {
            valueBytes = value.getBytes(StandardCharsets.UTF_8);
            digestBytes = newDigest(algorithm).digest(valueBytes);
            return toHex(digestBytes);
        } finally {
            clear(digestBytes);
            clear(valueBytes);
        }
    }

    private static void updateUtf8(MessageDigest target, String value) {
        byte[] valueBytes = null;
        try {
            valueBytes = value.getBytes(StandardCharsets.UTF_8);
            target.update(valueBytes);
        } finally {
            clear(valueBytes);
        }
    }

    private static byte[] encodeUtf8(char[] value) {
        int byteCount = 0;
        for (int index = 0; index < value.length; index++) {
            char character = value[index];
            if (character < 0x80) {
                byteCount++;
            } else if (character < 0x800) {
                byteCount += 2;
            } else if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw invalidInput();
                }
                byteCount += 4;
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalidInput();
            } else {
                byteCount += 3;
            }
            if (byteCount < 0) throw invalidInput();
        }

        byte[] encoded = new byte[byteCount];
        int output = 0;
        for (int index = 0; index < value.length; index++) {
            int character = value[index];
            if (character < 0x80) {
                encoded[output++] = (byte) character;
            } else if (character < 0x800) {
                encoded[output++] = (byte) (0xc0 | (character >>> 6));
                encoded[output++] = (byte) (0x80 | (character & 0x3f));
            } else if (Character.isHighSurrogate((char) character)) {
                int codePoint = Character.toCodePoint((char) character, value[++index]);
                encoded[output++] = (byte) (0xf0 | (codePoint >>> 18));
                encoded[output++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
                encoded[output++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
                encoded[output++] = (byte) (0x80 | (codePoint & 0x3f));
            } else {
                encoded[output++] = (byte) (0xe0 | (character >>> 12));
                encoded[output++] = (byte) (0x80 | ((character >>> 6) & 0x3f));
                encoded[output++] = (byte) (0x80 | (character & 0x3f));
            }
        }
        return encoded;
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required digest unavailable");
        }
    }

    private static String toHex(byte[] value) {
        char[] hexChars = new char[value.length * 2];
        try {
            for (int index = 0; index < value.length; index++) {
                int unsigned = value[index] & 0xff;
                hexChars[index * 2] = hexDigit(unsigned >>> 4);
                hexChars[index * 2 + 1] = hexDigit(unsigned & 0x0f);
            }
            return new String(hexChars);
        } finally {
            clear(hexChars);
        }
    }

    private static char hexDigit(int value) {
        return (char) (value < 10 ? '0' + value : 'a' + value - 10);
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private static IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("Invalid signing input");
    }

    public static final class Result {
        private final String scode;
        private final String sign;

        private Result(String scode, String sign) {
            this.scode = scode;
            this.sign = sign;
        }

        public String scode() {
            return scode;
        }

        public String sign() {
            return sign;
        }
    }
}
