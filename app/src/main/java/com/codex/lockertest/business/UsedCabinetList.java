package com.codex.lockertest.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Authenticated user's server-owned active-cabinet list. */
public final class UsedCabinetList {
    private final String userName;
    private final String mobile;
    private final List<UsedCabinet> cabinets;

    public UsedCabinetList(String userName, String mobile, List<UsedCabinet> cabinets) {
        this.userName = BusinessValues.text(userName, "User name", 256);
        this.mobile = BusinessValues.text(mobile, "Mobile", 256);
        if (cabinets == null) throw new IllegalArgumentException("Cabinet list is required");
        ArrayList<UsedCabinet> copy = new ArrayList<>(cabinets.size());
        Set<Long> recordIds = new HashSet<>();
        Set<Long> cabinetIds = new HashSet<>();
        for (UsedCabinet cabinet : cabinets) {
            if (cabinet == null
                    || !recordIds.add(cabinet.recordId())
                    || !cabinetIds.add(cabinet.fcId())) {
                throw new IllegalArgumentException("Cabinet records must be unique");
            }
            copy.add(cabinet);
        }
        this.cabinets = Collections.unmodifiableList(copy);
    }

    public String userName() { return userName; }
    public String mobile() { return mobile; }
    public List<UsedCabinet> cabinets() { return cabinets; }

    @Override public String toString() {
        return "UsedCabinetList{identity=<redacted>, count=" + cabinets.size() + "}";
    }
}
