package com.codex.lockertest.runtime;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerLayoutSource;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Produces the fixed three-board local-demo compatibility layout. */
public final class LocalDemoLegacyLayoutSource implements LockerLayoutSource {
    @Override
    public LockerLayoutSnapshot layoutFor(List<LockerZone> onlineZones) {
        if (onlineZones == null) {
            throw new IllegalArgumentException("Online zones are required");
        }
        boolean onlineA = false;
        boolean onlineB = false;
        boolean onlineC = false;
        for (LockerZone zone : onlineZones) {
            if (zone == null) {
                throw new IllegalArgumentException("Online zones cannot contain null");
            }
            if (zone == LockerZone.A) {
                onlineA = true;
            } else if (zone == LockerZone.B) {
                onlineB = true;
            } else if (zone == LockerZone.C) {
                onlineC = true;
            }
        }

        long version = (onlineA ? 1 : 0) | (onlineB ? 2 : 0) | (onlineC ? 4 : 0);
        return new LockerLayoutSnapshot(version, Arrays.asList(
                area(LockerZone.A, onlineA),
                area(LockerZone.B, onlineB),
                area(LockerZone.C, onlineC)));
    }

    private static LockerArea area(LockerZone zone, boolean enabled) {
        List<LockerSlot> slots = new ArrayList<>(12);
        for (int localLock = 1; localLock <= 12; localLock++) {
            int row = (localLock - 1) % 4 + 1;
            int column = (localLock - 1) / 4 + 1;
            String name = zone.name();
            slots.add(new LockerSlot(
                    "legacy-slot-" + name + "-" + twoDigits(localLock),
                    name + localLock,
                    row,
                    column,
                    enabled,
                    new LockerTarget(zone, zone.boardAddress(), localLock,
                            FeedbackPolarity.SHORT_WHEN_LOCKED)));
        }
        return new LockerArea("legacy-area-" + zone.name(), zone.name() + "区",
                Arrays.asList(new LockerPage("legacy-page-" + zone.name() + "-1", 1, 4, 8, slots)));
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
