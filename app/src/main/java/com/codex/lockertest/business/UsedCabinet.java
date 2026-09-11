package com.codex.lockertest.business;

/** One server-owned active cabinet record. */
public final class UsedCabinet {
    private final long recordId;
    private final long fcId;
    private final String name;
    private final String startUse;
    private final int useTimeMinutes;
    private final String useNotice;

    public UsedCabinet(long recordId, long fcId, String name, String startUse,
            int useTimeMinutes, String useNotice) {
        this.recordId = BusinessValues.positive(recordId, "Record id");
        this.fcId = BusinessValues.positive(fcId, "Cabinet id");
        this.name = BusinessValues.text(name, "Cabinet name", 256);
        this.startUse = BusinessValues.text(startUse, "Start time", 128);
        this.useTimeMinutes = BusinessValues.bounded(
                useTimeMinutes, 0, Integer.MAX_VALUE, "Use duration");
        this.useNotice = BusinessValues.formattedText(
                useNotice, "Use notice", 16384);
    }

    public long recordId() { return recordId; }
    public long fcId() { return fcId; }
    public String name() { return name; }
    public String startUse() { return startUse; }
    public int useTimeMinutes() { return useTimeMinutes; }
    public String useNotice() { return useNotice; }
}
