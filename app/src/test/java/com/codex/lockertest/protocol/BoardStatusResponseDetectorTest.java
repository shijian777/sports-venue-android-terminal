package com.codex.lockertest.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BoardStatusResponseDetectorTest {
    @Test
    public void statusZeroAndStatusElevenBothProveTheExpectedBoardIsPresent() {
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector(1).append(bytes(0x80, 0x01, 0x01, 0x00, 0x80), 5));
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector(1).append(bytes(0x80, 0x01, 0x01, 0x11, 0x91), 5));
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector(2).append(bytes(0x80, 0x02, 0x01, 0x00, 0x83), 5));
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector(3).append(bytes(0x80, 0x03, 0x01, 0x11, 0x93), 5));
    }

    @Test
    public void fragmentedResponseCompletesOnlyAfterItsFinalByte() {
        BoardStatusResponseDetector detector = detector(2);

        assertEquals(BoardStatusResponseDetector.Result.NONE,
                detector.append(bytes(0x80, 0x02), 2));
        assertEquals(BoardStatusResponseDetector.Result.NONE,
                detector.append(bytes(0x01, 0x11), 2));
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector.append(bytes(0x92), 1));
    }

    @Test
    public void noiseWrongFramesAndPartialOldFramesAreSkippedUntilAFullExpectedFrame() {
        BoardStatusResponseDetector detector = detector(2);

        byte[] stream = concat(
                bytes(0x55, 0x80, 0x01, 0x01),
                bytes(0x00, 0x80),
                bytes(0x80, 0x02, 0x01, 0x33, 0xB0),
                bytes(0x80, 0x03, 0x01, 0x00, 0x82),
                bytes(0x80, 0x02, 0x02, 0x00, 0x80),
                bytes(0x80, 0x02, 0x01, 0x00, 0x82),
                bytes(0x82, 0x02, 0x01, 0x11, 0x90),
                bytes(0x8A, 0x02, 0x01, 0x00, 0x89),
                bytes(0x80, 0x02, 0x01, 0x11, 0x92));

        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector.append(stream, stream.length));
    }

    @Test
    public void queryEchoWrongAddressWrongLockBadBccActiveAndUnlockFramesNeverCount() {
        BoardStatusResponseDetector detector = detector(1);
        byte[][] rejected = new byte[][]{
                bytes(0x80, 0x01, 0x01, 0x33, 0xB3),
                bytes(0x80, 0x02, 0x01, 0x00, 0x83),
                bytes(0x80, 0x01, 0x02, 0x00, 0x83),
                bytes(0x80, 0x01, 0x01, 0x00, 0x81),
                bytes(0x82, 0x01, 0x01, 0x11, 0x93),
                bytes(0x8A, 0x01, 0x01, 0x00, 0x8A)
        };

        for (byte[] frame : rejected) {
            assertEquals(BoardStatusResponseDetector.Result.NONE,
                    detector.append(frame, frame.length));
        }
    }

    @Test
    public void resetDiscardsAnIncompleteOldFrame() {
        BoardStatusResponseDetector detector = detector(3);
        assertEquals(BoardStatusResponseDetector.Result.NONE,
                detector.append(bytes(0x80, 0x03, 0x01), 3));

        detector.reset();

        assertEquals(BoardStatusResponseDetector.Result.NONE,
                detector.append(bytes(0x00, 0x82), 2));
        assertEquals(BoardStatusResponseDetector.Result.PRESENT,
                detector.append(bytes(0x80, 0x03, 0x01, 0x00, 0x82), 5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidExpectedAddressIsRejected() {
        detector(4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidAppendLengthIsRejected() {
        detector(1).append(bytes(0x80), 2);
    }

    private static BoardStatusResponseDetector detector(int boardAddress) {
        return new BoardStatusResponseDetector(boardAddress);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
