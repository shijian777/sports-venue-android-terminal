package com.codex.lockertest.ui;

public final class NumericKeypadModel {
    private static final char MASK = '\u2022';

    private final int maxLength;
    private final boolean masked;
    private final StringBuilder value = new StringBuilder();

    public NumericKeypadModel(int maxLength, boolean masked) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be greater than zero");
        }
        this.maxLength = maxLength;
        this.masked = masked;
    }

    public boolean pressDigit(char digit) {
        if (digit < '0' || digit > '9' || value.length() >= maxLength) {
            return false;
        }
        value.append(digit);
        return true;
    }

    public boolean delete() {
        if (value.length() == 0) {
            return false;
        }
        value.deleteCharAt(value.length() - 1);
        return true;
    }

    public void clear() {
        value.setLength(0);
    }

    public String rawValue() {
        return value.toString();
    }

    public String displayValue() {
        if (!masked) {
            return rawValue();
        }
        StringBuilder display = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            display.append(MASK);
        }
        return display.toString();
    }
}
