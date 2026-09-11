package com.codex.lockertest.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SerialConfigSelectionTest {
    @Test
    public void customActiveConfigMapsToEveryVisibleAdminControl() {
        SerialConfigSelection selection = SerialConfigSelection.from(
                new SerialConfig("/dev/ttyS3", 115200, 2, 7, 2, 0));

        assertEquals("/dev/ttyS3", selection.path());
        assertEquals("115200", selection.baudRate());
        assertEquals("7", selection.dataBits());
        assertEquals("2", selection.stopBits());
        assertEquals("Even", selection.parity());
        assertEquals("None", selection.flowControl());
    }
}

