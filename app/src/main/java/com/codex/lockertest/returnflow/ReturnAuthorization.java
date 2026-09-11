package com.codex.lockertest.returnflow;

import com.codex.lockertest.protocol.LockerProtocol;

import java.util.Arrays;

/** One server-issued, time-bounded authority to open a return locker. */
public final class ReturnAuthorization {
    private final long operationId;
    private final ReturnLocker locker;
    private final byte[] unlockCommand;
    private final byte[] expectedSuccessFrame;
    private final byte[] expectedFailureFrame;
    private final String completionToken;
    private final long expiresAt;

    public ReturnAuthorization(long operationId, ReturnLocker locker,
            byte[] unlockCommand, byte[] expectedSuccessFrame,
            byte[] expectedFailureFrame, String completionToken, long expiresAt) {
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation ID must be positive");
        }
        if (locker == null) {
            throw new IllegalArgumentException("Return locker is required");
        }
        if (expiresAt <= 0L) {
            throw new IllegalArgumentException("Expiry time must be positive");
        }
        this.operationId = operationId;
        this.locker = locker;
        this.unlockCommand = exactFrame(unlockCommand, LockerProtocol.unlockCommand(locker.target()),
                "Unlock command is not authorized for locker");
        this.expectedSuccessFrame = exactFrame(expectedSuccessFrame,
                LockerProtocol.successFrame(locker.target()),
                "Success frame is not authorized for locker");
        this.expectedFailureFrame = exactFrame(expectedFailureFrame,
                LockerProtocol.failureFrame(locker.target()),
                "Failure frame is not authorized for locker");
        if (completionToken == null || completionToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Completion token is required");
        }
        this.completionToken = completionToken;
        this.expiresAt = expiresAt;
    }

    public long operationId() {
        return operationId;
    }

    public ReturnLocker locker() {
        return locker;
    }

    public byte[] unlockCommand() {
        return unlockCommand.clone();
    }

    public byte[] expectedSuccessFrame() {
        return expectedSuccessFrame.clone();
    }

    public byte[] expectedFailureFrame() {
        return expectedFailureFrame.clone();
    }

    public String completionToken() {
        return completionToken;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean isExpiredAt(long now) {
        return now >= expiresAt;
    }

    private static byte[] exactFrame(byte[] value, byte[] expected, String message) {
        if (value == null || value.length != 5 || !Arrays.equals(value, expected)) {
            throw new IllegalArgumentException(message);
        }
        return value.clone();
    }
}
