package com.codex.lockertest.server;

/** An exact, legal JSON number token with canonical-integer conversion helpers. */
public final class JsonNumber {
    private final String rawToken;

    JsonNumber(String rawToken) {
        this.rawToken = rawToken;
    }

    public String rawToken() {
        return rawToken;
    }

    public int toInt(int minimum, int maximum) {
        if (minimum > maximum) throw JsonContractException.invalidRange();
        long value = canonicalLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                || value < minimum || value > maximum) {
            throw JsonContractException.integerOutOfRange();
        }
        return (int) value;
    }

    public long toLong(long minimum, long maximum) {
        if (minimum > maximum) throw JsonContractException.invalidRange();
        long value = canonicalLong();
        if (value < minimum || value > maximum) {
            throw JsonContractException.integerOutOfRange();
        }
        return value;
    }

    private long canonicalLong() {
        int length = rawToken.length();
        boolean negative = length > 0 && rawToken.charAt(0) == '-';
        int index = negative ? 1 : 0;
        if (index == length || (negative && length == 2 && rawToken.charAt(1) == '0')) {
            throw JsonContractException.canonicalIntegerRequired();
        }
        if (rawToken.charAt(index) == '0' && index + 1 != length) {
            throw JsonContractException.canonicalIntegerRequired();
        }

        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / 10;
        long result = 0;
        for (; index < length; index++) {
            char character = rawToken.charAt(index);
            if (character < '0' || character > '9') {
                throw JsonContractException.canonicalIntegerRequired();
            }
            int digit = character - '0';
            if (result < multiplyLimit) {
                throw JsonContractException.integerOutOfRange();
            }
            result *= 10;
            if (result < limit + digit) {
                throw JsonContractException.integerOutOfRange();
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber
                && rawToken.equals(((JsonNumber) other).rawToken);
    }

    @Override
    public int hashCode() {
        return rawToken.hashCode();
    }

    @Override
    public String toString() {
        return rawToken;
    }
}
