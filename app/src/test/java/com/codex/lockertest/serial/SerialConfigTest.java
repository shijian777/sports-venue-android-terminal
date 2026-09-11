package com.codex.lockertest.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class SerialConfigTest {
    @Test
    public void defaultsDriveTheManufacturerConfiguration() {
        SerialConfig config = SerialConfig.defaults();
        assertEquals("/dev/ttyS0", config.getPath());
        assertEquals(9600, config.getBaudRate());
        assertEquals(0, config.getParity());
        assertEquals(8, config.getDataBits());
        assertEquals(1, config.getStopBits());
        assertEquals(0, config.getFlags());
        assertEquals("/dev/ttyS0 · 9600 · 8N1 · Flow None", config.describe());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidDataBitsCannotReachNativeOpen() {
        new SerialConfig("/dev/ttyS0", 9600, 0, 9, 1, 0);
    }

    @Test
    public void parityDescriptionMatchesTheSelectedMode() {
        assertEquals("/dev/ttyS1 · 19200 · 7E2 · Flow None",
                new SerialConfig("/dev/ttyS1", 19200, 2, 7, 2, 0).describe());
    }

    @Test
    public void equalNormalizedConfigurationsHaveEqualHashes() {
        SerialConfig defaults = SerialConfig.defaults();
        SerialConfig equivalent =
                new SerialConfig("  /dev/ttyS0  ", 9600, 0, 8, 1, 0);

        assertEquals(defaults, equivalent);
        assertEquals(defaults.hashCode(), equivalent.hashCode());
    }

    @Test
    public void everyConfigurationFieldParticipatesInEquality() {
        SerialConfig defaults = SerialConfig.defaults();

        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS1", 9600, 0, 8, 1, 0));
        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS0", 19200, 0, 8, 1, 0));
        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS0", 9600, 1, 8, 1, 0));
        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS0", 9600, 0, 7, 1, 0));
        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS0", 9600, 0, 8, 2, 0));
        assertNotEquals(defaults,
                new SerialConfig("/dev/ttyS0", 9600, 0, 8, 1, 1));
    }
}
