package com.codex.lockertest.protocol;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BoundedLogBuffer {
    private final int maxCharacters;
    private final int maxLines;
    private final Deque<String> lines = new ArrayDeque<>();
    private int characterCount;

    public BoundedLogBuffer(int maxCharacters, int maxLines) {
        if (maxCharacters < 1 || maxLines < 1) {
            throw new IllegalArgumentException("日志上限必须大于 0");
        }
        this.maxCharacters = maxCharacters;
        this.maxLines = maxLines;
    }

    public synchronized void append(String line) {
        String value = line == null ? "null" : line;
        if (value.length() > maxCharacters) {
            value = value.substring(value.length() - maxCharacters);
        }
        int separator = lines.isEmpty() ? 0 : 1;
        lines.addLast(value);
        characterCount += value.length() + separator;
        trim();
    }

    public synchronized String snapshot() {
        StringBuilder builder = new StringBuilder(characterCount);
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    public synchronized void clear() {
        lines.clear();
        characterCount = 0;
    }

    private void trim() {
        while (lines.size() > maxLines || characterCount > maxCharacters) {
            String removed = lines.removeFirst();
            characterCount -= removed.length();
            if (!lines.isEmpty()) {
                characterCount--;
            }
        }
    }
}
