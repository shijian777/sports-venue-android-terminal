package com.codex.lockertest.business;

public final class AssignedCabinet {
    private final long fcId;

    public AssignedCabinet(long fcId) {
        this.fcId = BusinessValues.positive(fcId, "Assigned cabinet id");
    }

    public long fcId() { return fcId; }
}
