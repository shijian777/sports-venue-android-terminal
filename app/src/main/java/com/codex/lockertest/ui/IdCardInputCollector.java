package com.codex.lockertest.ui;

/** Collects one printable-ASCII keyboard-wedge scan without normalizing its payload. */
public final class IdCardInputCollector {
    private final int maxLength;
    private final StringBuilder characters = new StringBuilder();
    private long generation;

    public IdCardInputCollector(int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        this.maxLength = maxLength;
    }

    public boolean appendCharacter(char character) {
        if (character < 0x20 || character > 0x7E
                || characters.length() >= maxLength) {
            return false;
        }
        characters.append(character);
        advanceGeneration();
        return true;
    }

    /** Compatibility bridge for the original numeric reader. */
    public boolean appendDigit(char digit) {
        return digit >= '0' && digit <= '9' && appendCharacter(digit);
    }

    public int length() {
        return characters.length();
    }

    public long generation() {
        return generation;
    }

    public String finishIfCurrent(long expectedGeneration) {
        if (expectedGeneration != generation || characters.length() == 0) {
            return null;
        }
        return finish();
    }

    public String finish() {
        if (characters.length() == 0) {
            return null;
        }
        String value = characters.toString();
        characters.setLength(0);
        advanceGeneration();
        return value;
    }

    public void reset() {
        characters.setLength(0);
        advanceGeneration();
    }

    private void advanceGeneration() {
        generation++;
        if (generation <= 0L) {
            generation = 1L;
        }
    }
}
