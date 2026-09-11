package com.codex.lockertest.protocol;

public final class BoardStatusResponseDetector {
    public enum Result {
        NONE,
        PRESENT
    }

    private static final int FRAME_LENGTH = 5;
    private static final byte HEADER = (byte) 0x80;
    private static final byte LOCK_ONE = 0x01;
    private static final byte STATUS_OPEN = 0x00;
    private static final byte STATUS_CLOSED = 0x11;

    private final byte expectedBoardAddress;
    private final byte[] window = new byte[FRAME_LENGTH];
    private int windowLength;

    public BoardStatusResponseDetector(int expectedBoardAddress) {
        BoardStatusProtocol.validateBoardAddress(expectedBoardAddress);
        this.expectedBoardAddress = (byte) expectedBoardAddress;
    }

    public synchronized Result append(byte[] bytes, int length) {
        if (bytes == null || length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("Invalid received data length");
        }

        for (int index = 0; index < length; index++) {
            appendToWindow(bytes[index]);
            if (isExpectedResponse()) {
                windowLength = 0;
                return Result.PRESENT;
            }
        }
        return Result.NONE;
    }

    public synchronized void reset() {
        windowLength = 0;
    }

    private void appendToWindow(byte value) {
        if (windowLength < FRAME_LENGTH) {
            window[windowLength++] = value;
            return;
        }
        System.arraycopy(window, 1, window, 0, FRAME_LENGTH - 1);
        window[FRAME_LENGTH - 1] = value;
    }

    private boolean isExpectedResponse() {
        if (windowLength != FRAME_LENGTH
                || window[0] != HEADER
                || window[1] != expectedBoardAddress
                || window[2] != LOCK_ONE
                || (window[3] != STATUS_OPEN && window[3] != STATUS_CLOSED)) {
            return false;
        }
        return window[4] == BoardStatusProtocol.xor(
                window[0], window[1], window[2], window[3]);
    }
}
