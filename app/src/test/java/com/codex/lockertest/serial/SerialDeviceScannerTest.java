package com.codex.lockertest.serial;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SerialDeviceScannerTest {
    @Test
    public void recognizesOnlySupportedSerialNames() {
        assertTrue(SerialDeviceScanner.isCandidateName("ttyS0"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyUSB1"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyACM0"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyAMA2"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyHS3"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttymxc1"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyMT0"));
        assertTrue(SerialDeviceScanner.isCandidateName("ttyXRUSB0"));
        assertFalse(SerialDeviceScanner.isCandidateName("tty"));
        assertFalse(SerialDeviceScanner.isCandidateName("random"));
    }

    @Test
    public void scanReturnsPreferredFirstThenSortedCandidates() throws Exception {
        File dev = fixtureDirectory("scan");
        new File(dev, "ttyUSB1").createNewFile();
        new File(dev, "ttyS2").createNewFile();
        new File(dev, "random").createNewFile();
        File preferred = new File(dev, "ttyS0");
        preferred.createNewFile();

        List<String> values = SerialDeviceScanner.scan(dev, preferred);
        assertEquals(preferred.getAbsolutePath(), values.get(0));
        assertEquals(new File(dev, "ttyS2").getAbsolutePath(), values.get(1));
        assertEquals(new File(dev, "ttyUSB1").getAbsolutePath(), values.get(2));
        assertEquals(3, values.size());
    }

    @Test
    public void preferredDeviceSurvivesAnUnreadableDirectoryListing() throws Exception {
        File root = fixtureDirectory("fallback");
        File preferred = new File(root, "ttyS0");
        preferred.createNewFile();
        List<String> values = SerialDeviceScanner.scan(
                new File(root, "missing"), preferred);
        assertEquals(1, values.size());
        assertEquals(preferred.getAbsolutePath(), values.get(0));
    }

    private static File fixtureDirectory(String name) {
        File directory = new File("manual-build/test-fixtures/" + name);
        directory.mkdirs();
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        return directory;
    }
}
