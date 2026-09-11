package com.codex.lockertest.serial;

/** Immutable visible-control values derived from the actual active serial configuration. */
public final class SerialConfigSelection {
    private final String path;
    private final String baudRate;
    private final String dataBits;
    private final String stopBits;
    private final String parity;
    private final String flowControl;

    private SerialConfigSelection(
            String path,
            String baudRate,
            String dataBits,
            String stopBits,
            String parity,
            String flowControl) {
        this.path = path;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.flowControl = flowControl;
    }

    public static SerialConfigSelection from(SerialConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        String parity = config.getParity() == 1
                ? "Odd" : config.getParity() == 2 ? "Even" : "None";
        return new SerialConfigSelection(
                config.getPath(),
                Integer.toString(config.getBaudRate()),
                Integer.toString(config.getDataBits()),
                Integer.toString(config.getStopBits()),
                parity,
                "None");
    }

    public String path() {
        return path;
    }

    public String baudRate() {
        return baudRate;
    }

    public String dataBits() {
        return dataBits;
    }

    public String stopBits() {
        return stopBits;
    }

    public String parity() {
        return parity;
    }

    public String flowControl() {
        return flowControl;
    }
}

