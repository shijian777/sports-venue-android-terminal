package com.codex.lockertest.business;

/** Documented administrator login metadata. */
public final class AdminLogin {
    private final SessionToken token;
    private final String venueName;
    private final String deviceName;
    private final String deviceSerial;
    private final String areaName;
    private final String username;
    private final String name;

    public AdminLogin(SessionToken token, String venueName, String deviceName,
            String deviceSerial, String areaName, String username, String name) {
        if (token == null) throw new IllegalArgumentException("Admin token is required");
        this.token = token;
        this.venueName = BusinessValues.text(venueName, "Venue name", 256);
        this.deviceName = BusinessValues.text(deviceName, "Device name", 256);
        this.deviceSerial = BusinessValues.text(deviceSerial, "Device serial", 256);
        this.areaName = BusinessValues.text(areaName, "Area name", 256);
        this.username = BusinessValues.text(username, "Username", 256);
        this.name = BusinessValues.text(name, "Admin name", 256);
    }

    public SessionToken token() { return token; }
    public String venueName() { return venueName; }
    public String deviceName() { return deviceName; }
    public String deviceSerial() { return deviceSerial; }
    public String areaName() { return areaName; }
    public String username() { return username; }
    public String name() { return name; }
}
