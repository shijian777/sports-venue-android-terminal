package com.codex.lockertest.returnflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable server query payload for return lockers. */
public final class ReturnLockerList {
    private final List<ReturnLocker> lockers;

    private ReturnLockerList(List<ReturnLocker> lockers) {
        this.lockers = lockers;
    }

    public static ReturnLockerList of(List<ReturnLocker> source) {
        if (source == null) {
            throw new IllegalArgumentException("Locker list is required");
        }
        ArrayList<ReturnLocker> copy = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (ReturnLocker locker : source) {
            if (locker == null || !ids.add(locker.serverLockerId())) {
                throw new IllegalArgumentException("Locker list must contain unique non-null lockers");
            }
            copy.add(locker);
        }
        return new ReturnLockerList(Collections.unmodifiableList(copy));
    }

    public List<ReturnLocker> lockers() {
        return lockers;
    }

    public boolean isEmpty() {
        return lockers.isEmpty();
    }
}
