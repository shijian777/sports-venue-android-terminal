package com.codex.lockertest.serial;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.FeedbackPolarity;

public final class SerialWriteAttribution {
    public enum Origin {
        DISCOVERY,
        UNLOCK,
        DOOR_STATUS,
        RETURN_UNLOCK
    }

    private final Origin origin;
    private final int boardAddress;
    private final int localLock;
    private final FeedbackPolarity feedbackPolarity;

    private SerialWriteAttribution(
            Origin origin,
            int boardAddress,
            int localLock,
            FeedbackPolarity feedbackPolarity) {
        this.origin = origin;
        this.boardAddress = boardAddress;
        this.localLock = localLock;
        this.feedbackPolarity = feedbackPolarity;
    }

    public static SerialWriteAttribution discovery(int boardAddress) {
        if (boardAddress < 1 || boardAddress > 3) {
            throw new IllegalArgumentException("Board address must be between 1 and 3");
        }
        return new SerialWriteAttribution(Origin.DISCOVERY, boardAddress, 0, null);
    }

    public static SerialWriteAttribution unlock(LockerTarget target) {
        return forTarget(Origin.UNLOCK, target);
    }

    public static SerialWriteAttribution doorStatus(LockerTarget target) {
        return forTarget(Origin.DOOR_STATUS, target);
    }

    public static SerialWriteAttribution returnUnlock(LockerTarget target) {
        return forTarget(Origin.RETURN_UNLOCK, target);
    }

    private static SerialWriteAttribution forTarget(Origin origin, LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        return new SerialWriteAttribution(
                origin,
                target.boardAddress(),
                target.localLock(),
                target.feedbackPolarity());
    }

    public Origin origin() {
        return origin;
    }

    public int boardAddress() {
        return boardAddress;
    }

    public int localLock() {
        return localLock;
    }

    public FeedbackPolarity feedbackPolarity() {
        return feedbackPolarity;
    }
}
