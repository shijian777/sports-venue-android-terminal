package com.codex.lockertest.protocol;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BoundedLogBufferTest {
    @Test
    public void discardsOldLinesBeforeTheLogCanGrowWithoutLimit() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(24, 3);
        buffer.append("first");
        buffer.append("second");
        buffer.append("third");
        buffer.append("fourth");

        String snapshot = buffer.snapshot();
        assertFalse(snapshot.contains("first"));
        assertTrue(snapshot.contains("fourth"));
        assertTrue(snapshot.length() <= 24);
    }

    @Test
    public void aHugeSingleLineIsTrimmedToTheNewestCharacters() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(8, 3);
        buffer.append("0123456789ABC");
        assertTrue(buffer.snapshot().length() <= 8);
        assertTrue(buffer.snapshot().endsWith("6789ABC"));
    }

    @Test
    public void clearRemovesAllRetainedLines() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(40, 4);
        buffer.append("line");
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
    }
}
