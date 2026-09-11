package com.codex.lockertest.serial;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SerialDeviceScanner {
    private static final Pattern DEVICE_NAME = Pattern.compile(
            "^(?:ttyS|ttyUSB|ttyACM|ttyAMA|ttyHS|ttymxc|ttyMT|ttyXRUSB)[A-Za-z0-9]+$");

    private SerialDeviceScanner() {
    }

    public static boolean isCandidateName(String name) {
        return name != null && DEVICE_NAME.matcher(name).matches();
    }

    public static List<String> scan(File devDirectory, File preferredDevice) {
        Set<String> found = new LinkedHashSet<>();
        if (devDirectory != null) {
            File[] children = devDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && isCandidateName(child.getName())) {
                        found.add(child.getAbsolutePath());
                    }
                }
            }
        }

        String preferredPath = null;
        if (preferredDevice != null && preferredDevice.exists()) {
            preferredPath = preferredDevice.getAbsolutePath();
            found.remove(preferredPath);
        }

        List<String> sorted = new ArrayList<>(found);
        Collections.sort(sorted);
        if (preferredPath != null) {
            sorted.add(0, preferredPath);
        }
        return sorted;
    }

    public static List<String> scanSystemDevices() {
        return scan(new File("/dev"), new File("/dev/ttyS0"));
    }
}
