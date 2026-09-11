package com.codex.lockertest.serial;

import java.util.Objects;

public final class SerialConfig {
    private final String path;
    private final int baudRate;
    private final int parity;
    private final int dataBits;
    private final int stopBits;
    private final int flags;

    public SerialConfig(
            String path,
            int baudRate,
            int parity,
            int dataBits,
            int stopBits,
            int flags) {
        if (path == null || path.trim().isEmpty() || !path.trim().startsWith("/")) {
            throw new IllegalArgumentException("串口路径必须是绝对路径");
        }
        if (baudRate <= 0) {
            throw new IllegalArgumentException("波特率必须大于 0");
        }
        if (parity < 0 || parity > 2) {
            throw new IllegalArgumentException("校验位只支持 None、Odd、Even");
        }
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("数据位只支持 5、6、7、8");
        }
        if (stopBits < 1 || stopBits > 2) {
            throw new IllegalArgumentException("停止位只支持 1 或 2");
        }
        if (flags < 0) {
            throw new IllegalArgumentException("串口 flags 不能为负数");
        }
        this.path = path.trim();
        this.baudRate = baudRate;
        this.parity = parity;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.flags = flags;
    }

    public static SerialConfig defaults() {
        return new SerialConfig("/dev/ttyS0", 9600, 0, 8, 1, 0);
    }

    public String getPath() {
        return path;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public int getParity() {
        return parity;
    }

    public int getDataBits() {
        return dataBits;
    }

    public int getStopBits() {
        return stopBits;
    }

    public int getFlags() {
        return flags;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SerialConfig)) {
            return false;
        }
        SerialConfig that = (SerialConfig) other;
        return baudRate == that.baudRate
                && parity == that.parity
                && dataBits == that.dataBits
                && stopBits == that.stopBits
                && flags == that.flags
                && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, baudRate, parity, dataBits, stopBits, flags);
    }

    public String describe() {
        return path + " · " + baudRate + " · " + dataBits + parityCode()
                + stopBits + " · Flow None";
    }

    private char parityCode() {
        if (parity == 1) {
            return 'O';
        }
        if (parity == 2) {
            return 'E';
        }
        return 'N';
    }
}
