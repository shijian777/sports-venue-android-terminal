package com.codex.lockertest.model;

import com.codex.lockertest.protocol.FeedbackPolarity;

public final class LockerTarget {
    private final LockerZone zone;
    private final int boardAddress;
    private final int localLock;
    private final FeedbackPolarity feedbackPolarity;

    public LockerTarget(
            LockerZone zone,
            int boardAddress,
            int localLock,
            FeedbackPolarity feedbackPolarity) {
        if (zone == null) {
            throw new IllegalArgumentException("Locker zone is required");
        }
        if (boardAddress != zone.boardAddress()) {
            throw new IllegalArgumentException("Locker zone and board address must match");
        }
        if (localLock < 1 || localLock > 12) {
            throw new IllegalArgumentException("Local lock must be between 1 and 12");
        }
        if (feedbackPolarity == null) {
            throw new IllegalArgumentException("Feedback polarity is required");
        }
        this.zone = zone;
        this.boardAddress = boardAddress;
        this.localLock = localLock;
        this.feedbackPolarity = feedbackPolarity;
    }

    public LockerZone zone() {
        return zone;
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

    public String customerLabel() {
        return zone.name() + localLock;
    }
}
