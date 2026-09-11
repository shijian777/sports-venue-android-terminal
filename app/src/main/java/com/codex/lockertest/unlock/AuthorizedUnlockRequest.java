package com.codex.lockertest.unlock;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.returnflow.ReturnAuthorization;

import java.util.Arrays;

/** Immutable serial authority for one server-approved locker operation. */
public final class AuthorizedUnlockRequest {
    private final long operationId;
    private final LockerTarget target;
    private final byte[] unlockCommand;
    private final byte[] expectedSuccessFrame;
    private final byte[] expectedFailureFrame;

    public AuthorizedUnlockRequest(
            long operationId,
            LockerTarget target,
            byte[] unlockCommand,
            byte[] expectedSuccessFrame,
            byte[] expectedFailureFrame) {
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation ID must be positive");
        }
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        this.operationId = operationId;
        this.target = target;
        this.unlockCommand = exactFrame(
                unlockCommand,
                LockerProtocol.unlockCommand(target),
                "Unlock command does not match target");
        this.expectedSuccessFrame = exactFrame(
                expectedSuccessFrame,
                LockerProtocol.successFrame(target),
                "Success frame does not match target");
        this.expectedFailureFrame = exactFrame(
                expectedFailureFrame,
                LockerProtocol.failureFrame(target),
                "Failure frame does not match target");
    }

    public static AuthorizedUnlockRequest from(ReturnAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Return authorization is required");
        }
        return new AuthorizedUnlockRequest(
                authorization.operationId(),
                authorization.locker().target(),
                authorization.unlockCommand(),
                authorization.expectedSuccessFrame(),
                authorization.expectedFailureFrame());
    }

    public long operationId() {
        return operationId;
    }

    public LockerTarget target() {
        return target;
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

    private static byte[] exactFrame(byte[] value, byte[] expected, String message) {
        if (value == null || value.length != 5 || !Arrays.equals(value, expected)) {
            throw new IllegalArgumentException(message);
        }
        return value.clone();
    }
}
