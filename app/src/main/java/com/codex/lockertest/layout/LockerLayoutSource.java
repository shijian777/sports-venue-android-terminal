package com.codex.lockertest.layout;

import com.codex.lockertest.model.LockerZone;

import java.util.List;

/** Supplies an immutable locker layout for a set of online boards. */
public interface LockerLayoutSource {
    LockerLayoutSnapshot layoutFor(List<LockerZone> onlineZones);
}
