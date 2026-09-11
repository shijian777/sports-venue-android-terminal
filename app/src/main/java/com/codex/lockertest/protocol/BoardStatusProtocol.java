package com.codex.lockertest.protocol;

public final class BoardStatusProtocol {
    private static final byte HEADER = (byte) 0x80;
    private static final byte LOCK_ONE = 0x01;
    private static final byte QUERY_STATUS = 0x33;

    private BoardStatusProtocol() {
    }

    public static byte[] query(int boardAddress) {
        validateBoardAddress(boardAddress);
        byte[] frame = new byte[]{
                HEADER,
                (byte) boardAddress,
                LOCK_ONE,
                QUERY_STATUS,
                0
        };
        frame[4] = xor(frame[0], frame[1], frame[2], frame[3]);
        return frame;
    }

    static void validateBoardAddress(int boardAddress) {
        if (boardAddress < 1 || boardAddress > 3) {
            throw new IllegalArgumentException("Board address must be between 1 and 3");
        }
    }

    static byte xor(byte first, byte second, byte third, byte fourth) {
        return (byte) (first ^ second ^ third ^ fourth);
    }
}
