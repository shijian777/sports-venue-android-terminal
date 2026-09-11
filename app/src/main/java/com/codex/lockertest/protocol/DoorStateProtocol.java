package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;

/** Frames for one locker door's physical-state query. */
public final class DoorStateProtocol {
    private static final byte HEADER = (byte) 0x80;
    private static final byte QUERY_STATE = 0x33;

    private DoorStateProtocol() {
    }

    public static byte[] query(LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        byte[] frame = new byte[]{
                HEADER,
                (byte) target.boardAddress(),
                (byte) target.localLock(),
                QUERY_STATE,
                0
        };
        frame[4] = xor(frame[0], frame[1], frame[2], frame[3]);
        return frame;
    }

    static byte xor(byte first, byte second, byte third, byte fourth) {
        return (byte) (first ^ second ^ third ^ fourth);
    }
}
