package com.codex.lockertest.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable, validated logical locker layout at a particular version. */
public final class LockerLayoutSnapshot {
    private final long version;
    private final List<LockerArea> areas;

    public LockerLayoutSnapshot(long version, List<LockerArea> areas) {
        if (version < 0) {
            throw new IllegalArgumentException("Layout version must be nonnegative");
        }
        if (areas == null) {
            throw new IllegalArgumentException("Layout areas are required");
        }
        List<LockerArea> copy = new ArrayList<>(areas.size());
        Set<String> areaIds = new HashSet<>();
        Set<String> slotIds = new HashSet<>();
        Set<String> physicalTargets = new HashSet<>();
        for (LockerArea area : areas) {
            if (area == null) {
                throw new IllegalArgumentException("Layout areas cannot contain null");
            }
            if (!areaIds.add(area.id())) {
                throw new IllegalArgumentException("Area ids must be unique within a layout");
            }
            for (LockerPage page : area.pages()) {
                for (LockerSlot slot : page.slots()) {
                    if (!slotIds.add(slot.id())) {
                        throw new IllegalArgumentException("Slot ids must be unique within a layout");
                    }
                    String targetKey = slot.target().boardAddress() + ":" + slot.target().localLock();
                    if (!physicalTargets.add(targetKey)) {
                        throw new IllegalArgumentException("Physical targets must be unique within a layout");
                    }
                }
            }
            copy.add(area);
        }
        this.version = version;
        this.areas = Collections.unmodifiableList(copy);
    }

    public long version() { return version; }
    public long getVersion() { return version; }
    public List<LockerArea> areas() { return areas; }
    public List<LockerArea> getAreas() { return areas; }
}
