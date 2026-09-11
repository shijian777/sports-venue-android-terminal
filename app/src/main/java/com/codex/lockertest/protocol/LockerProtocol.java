package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

import java.util.Locale;

public final class LockerProtocol {
    private static final byte HEADER = (byte) 0x8A;
    private static final byte ACTIVE_FEEDBACK = 0x11;
    private static final byte INACTIVE_FEEDBACK = 0x00;

    private LockerProtocol() {
    }

    public static byte[] unlockCommand(LockerTarget target) {
        return frame(target, ACTIVE_FEEDBACK);
    }

    public static byte[] successFrame(LockerTarget target) {
        return frame(target, target.feedbackPolarity() == FeedbackPolarity.SHORT_WHEN_LOCKED
                ? INACTIVE_FEEDBACK : ACTIVE_FEEDBACK);
    }

    public static byte[] failureFrame(LockerTarget target) {
        return frame(target, target.feedbackPolarity() == FeedbackPolarity.SHORT_WHEN_LOCKED
                ? ACTIVE_FEEDBACK : INACTIVE_FEEDBACK);
    }

    public static byte[] unlockCommand(int lockerNumber) {
        return unlockCommand(legacyTarget(lockerNumber));
    }

    public static byte[] successFrame(int lockerNumber) {
        return successFrame(legacyTarget(lockerNumber));
    }

    public static byte[] failureFrame(int lockerNumber) {
        return failureFrame(legacyTarget(lockerNumber));
    }

    public static String displayNumber(int lockerNumber) {
        validate(lockerNumber);
        return String.format(Locale.US, "%03d", lockerNumber);
    }

    public static boolean isValidLocker(int lockerNumber) {
        return lockerNumber >= 1 && lockerNumber <= 12;
    }

    private static byte[] frame(LockerTarget target, byte feedback) {
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        byte[] frame = new byte[]{
                HEADER,
                (byte) target.boardAddress(),
                (byte) target.localLock(),
                feedback,
                0
        };
        frame[4] = (byte) (frame[0] ^ frame[1] ^ frame[2] ^ frame[3]);
        return frame;
    }

    private static LockerTarget legacyTarget(int lockerNumber) {
        validate(lockerNumber);
        return new LockerTarget(
                LockerZone.A, LockerZone.A.boardAddress(), lockerNumber,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static void validate(int lockerNumber) {
        if (!isValidLocker(lockerNumber)) {
            throw new IllegalArgumentException("Locker number must be between 1 and 12");
        }
    }
}
