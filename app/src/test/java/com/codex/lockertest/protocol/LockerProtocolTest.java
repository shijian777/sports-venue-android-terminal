package com.codex.lockertest.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

import org.junit.Test;

public final class LockerProtocolTest {
    @Test
    public void boardOneCommandsAndLockedShortResultsMatchConfirmedLiterals() {
        String[] commands = {
                "8A0101119B", "8A01021198", "8A01031199", "8A0104119E",
                "8A0105119F", "8A0106119C", "8A0107119D", "8A01081192",
                "8A01091193", "8A010A1190", "8A010B1191", "8A010C1196"
        };
        String[] successes = {
                "8A0101008A", "8A01020089", "8A01030088", "8A0104008F",
                "8A0105008E", "8A0106008D", "8A0107008C", "8A01080083",
                "8A01090082", "8A010A0081", "8A010B0080", "8A010C0087"
        };

        for (int lock = 1; lock <= 12; lock++) {
            LockerTarget target = new LockerTarget(
                    LockerZone.A, 1, lock, FeedbackPolarity.SHORT_WHEN_LOCKED);
            assertArrayEquals(HexCodec.decode(commands[lock - 1]), LockerProtocol.unlockCommand(target));
            assertArrayEquals(HexCodec.decode(successes[lock - 1]), LockerProtocol.successFrame(target));
            assertArrayEquals(HexCodec.decode(commands[lock - 1]), LockerProtocol.failureFrame(target));
        }
    }

    @Test
    public void boardsTwoAndThreeUseTheirAddressesAndXorChecksum() {
        LockerTarget b12 = new LockerTarget(
                LockerZone.B, 2, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);
        LockerTarget c12 = new LockerTarget(
                LockerZone.C, 3, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);

        assertArrayEquals(HexCodec.decode("8A020C1195"), LockerProtocol.unlockCommand(b12));
        assertArrayEquals(HexCodec.decode("8A020C0084"), LockerProtocol.successFrame(b12));
        assertArrayEquals(HexCodec.decode("8A030C1194"), LockerProtocol.unlockCommand(c12));
        assertArrayEquals(HexCodec.decode("8A030C0085"), LockerProtocol.successFrame(c12));
    }

    @Test
    public void openShortPolarityReversesTerminalFrames() {
        LockerTarget target = new LockerTarget(
                LockerZone.B, 2, 12, FeedbackPolarity.SHORT_WHEN_OPEN);

        assertArrayEquals(HexCodec.decode("8A020C1195"), LockerProtocol.successFrame(target));
        assertArrayEquals(HexCodec.decode("8A020C0084"), LockerProtocol.failureFrame(target));
    }

    @Test
    public void targetLabelsAreCustomerFacingZoneAndLocalLock() {
        assertEquals("A1", new LockerTarget(
                LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED).customerLabel());
        assertEquals("C12", new LockerTarget(
                LockerZone.C, 3, 12, FeedbackPolarity.SHORT_WHEN_OPEN).customerLabel());
        assertEquals(1, LockerZone.A.boardAddress());
        assertEquals(2, LockerZone.B.boardAddress());
        assertEquals(3, LockerZone.C.boardAddress());
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetRejectsMismatchedZoneAndBoard() {
        new LockerTarget(LockerZone.B, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetRejectsLocalLockOutsideRange() {
        new LockerTarget(LockerZone.A, 1, 13, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    @Test
    public void legacyIntegerOverloadsDelegateToBoardOneLockedShortWithCorrectXor() {
        assertArrayEquals(HexCodec.decode("8A010C1196"), LockerProtocol.unlockCommand(12));
        assertArrayEquals(HexCodec.decode("8A010C0087"), LockerProtocol.successFrame(12));
        assertArrayEquals(HexCodec.decode("8A010C1196"), LockerProtocol.failureFrame(12));
    }

    @Test
    public void returnedFramesAreFreshDefensiveArrays() {
        LockerTarget target = new LockerTarget(
                LockerZone.B, 2, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);
        byte[] command = LockerProtocol.unlockCommand(target);
        byte[] success = LockerProtocol.successFrame(target);
        byte[] failure = LockerProtocol.failureFrame(target);

        command[0] = 0;
        success[0] = 0;
        failure[0] = 0;

        assertArrayEquals(HexCodec.decode("8A020C1195"), LockerProtocol.unlockCommand(target));
        assertArrayEquals(HexCodec.decode("8A020C0084"), LockerProtocol.successFrame(target));
        assertArrayEquals(HexCodec.decode("8A020C1195"), LockerProtocol.failureFrame(target));
    }

    @Test
    public void validLockerRangeIsExactlyOneThroughTwelve() {
        assertFalse(LockerProtocol.isValidLocker(0));
        for (int locker = 1; locker <= 12; locker++) {
            assertTrue(LockerProtocol.isValidLocker(locker));
        }
        assertFalse(LockerProtocol.isValidLocker(13));
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyUnlockCommandRejectsLockerBelowRange() {
        LockerProtocol.unlockCommand(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void displayNumberRejectsLockerBelowRange() {
        LockerProtocol.displayNumber(0);
    }
}
