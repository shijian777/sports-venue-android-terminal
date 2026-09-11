package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class IdCardScanSessionTest {
    @Test
    public void firstCharacterBindsAFullQrFrameToOneInputDevice() {
        String qr = "111993413628001787216027-00144049324404404044044~712~1~3~"
                + "30303030303137373331";
        IdCardScanSession session = new IdCardScanSession(4096);
        assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                session.appendCharacter(7, qr.charAt(0)));
        assertEquals(IdCardScanSession.AppendResult.IGNORED_OTHER_DEVICE,
                session.appendCharacter(8, '9'));
        assertNull(session.finish(8));
        for (int index = 1; index < qr.length(); index++) {
            assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                    session.appendCharacter(7, qr.charAt(index)));
        }

        IdCardScanSession.Completion completion = session.finish(7);

        assertTrue(completion.isValidFrame());
        assertEquals(qr, completion.value());
        assertEquals(7, completion.deviceId());
        assertEquals(77, completion.length());
    }

    @Test
    public void overflowPoisonsTheEntireFrameUntilItsOwnTerminator() {
        IdCardScanSession session = new IdCardScanSession(3);
        assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                session.appendCharacter(4, '1'));
        assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                session.appendCharacter(4, '2'));
        assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                session.appendCharacter(4, '3'));
        assertEquals(IdCardScanSession.AppendResult.FRAME_INVALID,
                session.appendCharacter(4, '4'));
        assertEquals(IdCardScanSession.AppendResult.FRAME_INVALID,
                session.appendCharacter(4, '0'));
        assertEquals(IdCardScanSession.AppendResult.FRAME_INVALID,
                session.appendCharacter(4, '0'));

        IdCardScanSession.Completion rejected = session.finish(4);

        assertFalse(rejected.isValidFrame());
        assertNull(rejected.value());
        assertEquals(3, rejected.length());

        assertEquals(IdCardScanSession.AppendResult.ACCEPTED,
                session.appendCharacter(4, '0'));
        assertEquals("0", session.finish(4).value());
    }

    @Test
    public void staleIdleTokenAndResetCannotFinishANewerFrame() {
        IdCardScanSession session = new IdCardScanSession(32);
        session.appendCharacter(2, '0');
        long stale = session.generation();
        session.appendCharacter(2, '1');

        assertNull(session.finishIfCurrent(stale));
        long current = session.generation();
        session.reset();
        assertNull(session.finishIfCurrent(current));
        assertNull(session.finish(2));
    }

    @Test
    public void emptyAndWrongDeviceTerminatorsNeverCompleteAFrame() {
        IdCardScanSession session = new IdCardScanSession(32);
        assertNull(session.finish(3));
        session.appendCharacter(-1, '0');
        assertNull(session.finish(3));
        assertEquals("0", session.finish(-1).value());
    }
}
