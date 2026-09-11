package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

public final class LockerResponseDetector {
    public enum Result {
        NONE,
        SUCCESS,
        FAILURE
    }

    private final PatternMatcher successMatcher;
    private final PatternMatcher failureMatcher;

    public LockerResponseDetector(LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        successMatcher = new PatternMatcher(LockerProtocol.successFrame(target));
        failureMatcher = new PatternMatcher(LockerProtocol.failureFrame(target));
    }

    public LockerResponseDetector(int lockerNumber) {
        this(new LockerTarget(
                LockerZone.A, LockerZone.A.boardAddress(), lockerNumber,
                FeedbackPolarity.SHORT_WHEN_LOCKED));
    }

    public synchronized Result append(byte[] bytes, int length) {
        if (bytes == null || length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("Invalid received data length");
        }

        for (int index = 0; index < length; index++) {
            byte value = bytes[index];
            if (successMatcher.append(value)) {
                return Result.SUCCESS;
            }
            if (failureMatcher.append(value)) {
                return Result.FAILURE;
            }
        }
        return Result.NONE;
    }

    public synchronized void reset() {
        successMatcher.reset();
        failureMatcher.reset();
    }

    private static final class PatternMatcher {
        private final byte[] pattern;
        private int matched;

        private PatternMatcher(byte[] pattern) {
            this.pattern = pattern;
        }

        private boolean append(byte value) {
            if (value == pattern[matched]) {
                matched++;
                if (matched == pattern.length) {
                    matched = 0;
                    return true;
                }
            } else {
                matched = value == pattern[0] ? 1 : 0;
            }
            return false;
        }

        private void reset() {
            matched = 0;
        }
    }
}
