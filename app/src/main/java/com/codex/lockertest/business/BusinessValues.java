package com.codex.lockertest.business;

final class BusinessValues {
    private BusinessValues() { }

    static String text(String value, String name, int maximumLength) {
        if (value == null || value.length() == 0 || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        boolean nonSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                nonSpace = true;
            }
        }
        if (!nonSpace) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    static String formattedText(String value, String name, int maximumLength) {
        if (value == null || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside its range");
        }
        return value;
    }
}
