package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;

/** Server-authorized locker metadata for the return journey. */
public final class ReturnLocker {
    private final String serverLockerId;
    private final String displayLabel;
    private final String areaDisplayName;
    private final LockerTarget target;

    public ReturnLocker(String serverLockerId, String displayLabel,
            String areaDisplayName, LockerTarget target) {
        this.serverLockerId = required(serverLockerId, "Server locker ID is required");
        this.displayLabel = required(displayLabel, "Display label is required");
        this.areaDisplayName = required(areaDisplayName, "Area display name is required");
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        this.target = target;
    }

    public String serverLockerId() {
        return serverLockerId;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public String areaDisplayName() {
        return areaDisplayName;
    }

    public LockerTarget target() {
        return target;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReturnLocker)) {
            return false;
        }
        ReturnLocker locker = (ReturnLocker) other;
        return serverLockerId.equals(locker.serverLockerId)
                && displayLabel.equals(locker.displayLabel)
                && areaDisplayName.equals(locker.areaDisplayName)
                && sameTarget(target, locker.target);
    }

    @Override
    public int hashCode() {
        int result = serverLockerId.hashCode();
        result = 31 * result + displayLabel.hashCode();
        result = 31 * result + areaDisplayName.hashCode();
        result = 31 * result + target.zone().hashCode();
        result = 31 * result + target.boardAddress();
        result = 31 * result + target.localLock();
        result = 31 * result + target.feedbackPolarity().hashCode();
        return result;
    }

    private static boolean sameTarget(LockerTarget first, LockerTarget second) {
        return first.zone() == second.zone()
                && first.boardAddress() == second.boardAddress()
                && first.localLock() == second.localLock()
                && first.feedbackPolarity() == second.feedbackPolarity();
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
