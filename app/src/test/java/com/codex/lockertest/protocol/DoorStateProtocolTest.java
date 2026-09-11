package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

public final class DoorStateProtocolTest {
    @Test
    public void queryUsesTheManufacturerLiteralForA1AndB2() {
        assertArrayEquals(bytes(0x80, 0x01, 0x01, 0x33, 0xB3),
                DoorStateProtocol.query(target(LockerZone.A, 1)));
        assertArrayEquals(bytes(0x80, 0x02, 0x02, 0x33, 0xB3),
                DoorStateProtocol.query(target(LockerZone.B, 2)));
    }

    @Test
    public void queryReturnsFreshBytesThatCannotBePoisonedByTheCaller() {
        byte[] first = DoorStateProtocol.query(target(LockerZone.A, 1));
        byte[] second = DoorStateProtocol.query(target(LockerZone.A, 1));

        assertNotSame(first, second);
        first[0] = 0;
        assertArrayEquals(bytes(0x80, 0x01, 0x01, 0x33, 0xB3), second);
    }

    private static LockerTarget target(LockerZone zone, int localLock) {
        return new LockerTarget(zone, zone.boardAddress(), localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
