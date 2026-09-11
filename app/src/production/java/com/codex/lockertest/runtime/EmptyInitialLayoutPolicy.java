package com.codex.lockertest.runtime;

import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.model.LockerZone;

import java.util.List;

/** Provides no customer layout before the production server layout protocol exists. */
public final class EmptyInitialLayoutPolicy implements InitialLayoutPolicy {
    @Override
    public LockerLayoutSnapshot initialLayout() {
        return null;
    }

    @Override
    public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
        return null;
    }
}
