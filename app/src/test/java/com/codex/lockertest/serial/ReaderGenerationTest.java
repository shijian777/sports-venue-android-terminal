package com.codex.lockertest.serial;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ReaderGenerationTest {
    @Test
    public void staleReaderKeepsItsCapturedInputAndCannotAffectReplacement() {
        ReaderGeneration<String> generations = new ReaderGeneration<>();
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicInteger currentCallbacks = new AtomicInteger();
        ReaderGeneration.Lease<String> oldReader = generations.activate("old-input");

        assertSame("old-input", oldReader.resource());
        assertTrue(generations.runIfCurrent(oldReader, oldCallbacks::incrementAndGet));
        assertTrue(generations.clear(oldReader));
        ReaderGeneration.Lease<String> currentReader = generations.activate("new-input");

        assertSame("old-input", oldReader.resource());
        assertSame("new-input", currentReader.resource());
        assertFalse(generations.runIfCurrent(oldReader, oldCallbacks::incrementAndGet));
        assertFalse(generations.clear(oldReader));
        assertTrue(generations.runIfCurrent(
                currentReader, currentCallbacks::incrementAndGet));
        assertTrue(generations.isCurrent(currentReader));
        assertTrue(oldCallbacks.get() == 1);
        assertTrue(currentCallbacks.get() == 1);
    }

    @Test(expected = IllegalStateException.class)
    public void replacementCannotActivateBeforeCurrentReaderIsDetached() {
        ReaderGeneration<String> generations = new ReaderGeneration<>();
        generations.activate("old-input");

        generations.activate("new-input");
    }
}
