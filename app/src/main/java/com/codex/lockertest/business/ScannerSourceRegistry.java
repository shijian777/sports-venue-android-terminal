package com.codex.lockertest.business;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Explicit hardware-source roles; runtime Android device IDs are never persisted. */
public final class ScannerSourceRegistry {
    public interface Store {
        int read(String key);
        boolean write(String key, int type);
    }
    public static final class Device {
        private final int id;
        private final String descriptor;
        private final int vendor;
        private final int product;
        private final boolean physicalKeyboard;
        public Device(int id, String descriptor, int vendor, int product, boolean physicalKeyboard) {
            this.id = id; this.descriptor = descriptor; this.vendor = vendor;
            this.product = product; this.physicalKeyboard = physicalKeyboard;
        }
        public int id() { return id; }
        public String identityToken() { return key(); }
        private String key() {
            if (!physicalKeyboard || id < 0 || vendor < 0 || product < 0
                    || descriptor == null || descriptor.isEmpty() || descriptor.length() > 1024) return null;
            for (int index = 0; index < descriptor.length(); index++)
                if (Character.isISOControl(descriptor.charAt(index))) return null;
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                        (vendor + ":" + product + ":" + descriptor).getBytes(StandardCharsets.UTF_8));
                StringBuilder key = new StringBuilder("reader.v1.");
                for (byte value : bytes) {
                    key.append(Character.forDigit((value & 255) >>> 4, 16));
                    key.append(Character.forDigit(value & 15, 16));
                }
                return key.toString();
            } catch (NoSuchAlgorithmException unavailable) { return null; }
        }
    }
    private final Store store;
    public ScannerSourceRegistry(Store store) {
        if (store == null) throw new IllegalArgumentException("Source storage required");
        this.store = store;
    }
    public int resolve(List<Device> connected, int id) {
        String key = uniqueKey(connected, id);
        if (key == null) return 0;
        try {
            int type = store.read(key);
            return type == 2 || type == 4 ? type : 0;
        } catch (RuntimeException failure) { return 0; }
    }
    public boolean assign(List<Device> connected, int id, int type) {
        if (type != 0 && type != 2 && type != 4) return false;
        String key = uniqueKey(connected, id);
        if (key == null) return false;
        try { return store.write(key, type); }
        catch (RuntimeException failure) { return false; }
    }
    public boolean configurable(List<Device> connected, int id) { return uniqueKey(connected, id) != null; }
    public boolean assignExpected(List<Device> connected, int id, String expectedIdentity, int type) {
        return expectedIdentity != null && expectedIdentity.equals(uniqueKey(connected, id)) && assign(connected, id, type);
    }
    private static String uniqueKey(List<Device> connected, int id) {
        if (connected == null) return null;
        String key = null;
        int ids = 0;
        for (Device device : connected) {
            if (device != null && device.id == id) { ids++; key = device.key(); }
        }
        if (ids != 1 || key == null) return null;
        int matches = 0;
        for (Device device : connected) if (device != null && key.equals(device.key())) matches++;
        return matches == 1 ? key : null;
    }
}
