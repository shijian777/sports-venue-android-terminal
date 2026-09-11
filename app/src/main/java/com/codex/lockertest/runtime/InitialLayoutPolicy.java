package com.codex.lockertest.runtime;

import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.model.LockerZone;

import java.util.List;

public interface InitialLayoutPolicy {
    LockerLayoutSnapshot initialLayout();
    LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones);
}
