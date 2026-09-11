package com.codex.lockertest.runtime;

import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.model.LockerZone;

import java.util.Collections;
import java.util.List;

/** Local-demo initial and discovery layouts backed by its own legacy fixture source. */
public final class LocalDemoInitialLayoutPolicy implements InitialLayoutPolicy {
    private final LocalDemoLegacyLayoutSource source = new LocalDemoLegacyLayoutSource();

    @Override
    public LockerLayoutSnapshot initialLayout() {
        return source.layoutFor(Collections.<LockerZone>emptyList());
    }

    @Override
    public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
        return source.layoutFor(onlineZones);
    }
}
