package com.codex.lockertest.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReceiveLogBatcherTest {
    @Test
    public void staleReaderAndFlushCannotBlockOrClearTheNewGeneration() {
        ReceiveLogBatcher batcher = new ReceiveLogBatcher(32);
        batcher.activate(1L);
        assertTrue(batcher.enqueue(1L, "AA"));

        batcher.invalidate(1L);
        batcher.activate(2L);
        assertFalse(batcher.enqueue(1L, "OLD"));
        assertTrue(batcher.enqueue(2L, "BB"));

        assertEquals("", batcher.flush(1L));
        assertFalse(batcher.enqueue(2L, "CC"));
        assertEquals("BB CC", batcher.flush(2L));
        assertTrue(batcher.enqueue(2L, "DD"));
    }

    @Test
    public void pendingTextNeverExceedsTheConfiguredHardLimit() {
        ReceiveLogBatcher batcher = new ReceiveLogBatcher(8);
        batcher.activate(7L);

        assertTrue(batcher.enqueue(7L, "123456"));
        assertFalse(batcher.enqueue(7L, "7890"));

        assertTrue(batcher.pendingLength() <= 8);
        assertEquals("456 7890", batcher.flush(7L));
        assertEquals(0, batcher.pendingLength());
    }
}
