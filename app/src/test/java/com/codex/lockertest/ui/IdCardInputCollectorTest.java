package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class IdCardInputCollectorTest {
    @Test
    public void completeQrPayloadPreservesEveryPrintableCharacterAndItsOrder() {
        String qr = "111993413628001787216027-00144049324404404044044~712~1~3~"
                + "30303030303137373331";
        IdCardInputCollector collector = new IdCardInputCollector(4096);
        for (char character : qr.toCharArray()) {
            assertTrue(collector.appendCharacter(character));
        }
        long completionToken = collector.generation();

        assertEquals(77, collector.length());
        assertEquals(qr, collector.finishIfCurrent(completionToken));
        assertEquals(0, collector.length());
        assertNull(collector.finishIfCurrent(completionToken));
    }

    @Test
    public void staleIdleCompletionCannotSubmitAReplacementScan() {
        IdCardInputCollector collector = new IdCardInputCollector(32);
        assertTrue(collector.appendCharacter('0'));
        long staleToken = collector.generation();
        assertTrue(collector.appendCharacter('1'));

        assertNull(collector.finishIfCurrent(staleToken));
        assertEquals("01", collector.finishIfCurrent(collector.generation()));
    }

    @Test
    public void resetInvalidatesQueuedCompletionAndClearsPartialDigits() {
        IdCardInputCollector collector = new IdCardInputCollector(32);
        assertTrue(collector.appendCharacter('1'));
        long staleToken = collector.generation();

        collector.reset();

        assertEquals(0, collector.length());
        assertNull(collector.finishIfCurrent(staleToken));
        assertNull(collector.finish());
    }

    @Test
    public void printableAsciiIsAcceptedWithoutCreatingABusinessLengthRule() {
        IdCardInputCollector collector = new IdCardInputCollector(3);

        assertFalse(collector.appendCharacter('\n'));
        assertFalse(collector.appendCharacter('\uFF11'));
        assertTrue(collector.appendCharacter('0'));
        assertTrue(collector.appendCharacter('-'));
        assertTrue(collector.appendCharacter('~'));
        assertFalse(collector.appendCharacter('A'));
        assertEquals("0-~", collector.finish());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonpositiveMaximumLengthIsRejected() {
        new IdCardInputCollector(0);
    }
}
