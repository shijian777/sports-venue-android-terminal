package com.codex.lockertest.business;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import static org.junit.Assert.*;

public class ScannerSourceRegistryTest {
    @Test public void persistsExplicitRoleAcrossRuntimeIdChanges() {
        MemoryStore store = new MemoryStore();
        ScannerSourceRegistry registry = new ScannerSourceRegistry(store);
        ScannerSourceRegistry.Device reader = device(6, "card-reader", 123, 456);
        assertEquals(0, registry.resolve(Collections.singletonList(reader), 6));
        assertTrue(registry.assign(Collections.singletonList(reader), 6, 4));
        ScannerSourceRegistry reloaded = new ScannerSourceRegistry(store);
        assertEquals(4, reloaded.resolve(Collections.singletonList(device(17, "card-reader", 123, 456)), 17));
        assertEquals(0, reloaded.resolve(Collections.singletonList(device(6, "another-device", 123, 456)), 6));
    }

    @Test public void sameVendorDevicesRemainSeparateAndNeverInferFromCredential() {
        ScannerSourceRegistry registry = new ScannerSourceRegistry(new MemoryStore());
        java.util.List<ScannerSourceRegistry.Device> devices = Arrays.asList(
                device(2, "scanner", 10, 20), device(3, "reader", 10, 20));
        assertTrue(registry.assign(devices, 2, 2));
        assertTrue(registry.assign(devices, 3, 4));
        assertEquals(2, registry.resolve(devices, 2));
        assertEquals(4, registry.resolve(devices, 3));
    }

    @Test public void duplicateStableIdentityIsAmbiguousAndCannotBeAssigned() {
        ScannerSourceRegistry registry = new ScannerSourceRegistry(new MemoryStore());
        ScannerSourceRegistry.Device device = device(2, "same", 10, 20);
        assertTrue(registry.assign(Collections.singletonList(device), 2, 2));
        java.util.List<ScannerSourceRegistry.Device> ambiguous = Arrays.asList(device, device(3, "same", 10, 20));
        assertEquals(0, registry.resolve(ambiguous, 2));
        assertFalse(registry.assign(ambiguous, 2, 4));
    }

    @Test public void wrongVidPidVirtualMissingOrInvalidDescriptorNeverResolve() {
        ScannerSourceRegistry registry = new ScannerSourceRegistry(new MemoryStore());
        assertTrue(registry.assign(Collections.singletonList(device(2, "same", 10, 20)), 2, 4));
        assertEquals(0, registry.resolve(Collections.singletonList(device(2, "same", 10, 21)), 2));
        for (ScannerSourceRegistry.Device bad : Arrays.asList(
                device(-1, "same", 10, 20), device(2, "", 10, 20), device(2, "bad\nname", 10, 20),
                new ScannerSourceRegistry.Device(2, "same", 10, 20, false))) {
            assertFalse(registry.assign(Collections.singletonList(bad), bad.id(), 4));
            assertEquals(0, registry.resolve(Collections.singletonList(bad), bad.id()));
        }
    }

    @Test public void removalAndFailedPersistenceDoNotClaimConfiguration() {
        MemoryStore store = new MemoryStore();
        ScannerSourceRegistry registry = new ScannerSourceRegistry(store);
        java.util.List<ScannerSourceRegistry.Device> devices = Collections.singletonList(device(1, "test", 10, 20));
        assertTrue(registry.assign(devices, 1, 2));
        assertTrue(registry.assign(devices, 1, 0));
        assertEquals(0, registry.resolve(devices, 1));
        store.fail = true;
        assertFalse(registry.assign(devices, 1, 4));
        assertEquals(0, registry.resolve(devices, 1));
        assertFalse(registry.assign(devices, 1, 3));
    }

    @Test public void corruptStorageOrStorageErrorFailsClosed() {
        ScannerSourceRegistry registry = new ScannerSourceRegistry(new ScannerSourceRegistry.Store() {
            public int read(String key) { throw new IllegalStateException(); }
            public boolean write(String key, int type) { throw new IllegalStateException(); }
        });
        java.util.List<ScannerSourceRegistry.Device> devices = Collections.singletonList(device(2, "reader", 1, 2));
        assertEquals(0, registry.resolve(devices, 2));
        assertFalse(registry.assign(devices, 2, 4));
    }

    @Test public void deviceReplacementBetweenSelectionAndSaveDoesNotConfigureNewDevice() {
        ScannerSourceRegistry registry = new ScannerSourceRegistry(new MemoryStore());
        ScannerSourceRegistry.Device selected = device(2, "selected-reader", 1, 2);
        List<ScannerSourceRegistry.Device> replaced = Collections.singletonList(device(2, "replacement", 1, 2));
        assertFalse(registry.assignExpected(replaced, 2, selected.identityToken(), 4));
        assertEquals(0, registry.resolve(replaced, 2));
        List<ScannerSourceRegistry.Device> original = Collections.singletonList(selected);
        assertTrue(registry.assignExpected(original, 2, selected.identityToken(), 4));
    }

    private static ScannerSourceRegistry.Device device(int id, String descriptor, int vendor, int product) {
        return new ScannerSourceRegistry.Device(id, descriptor, vendor, product, true);
    }
    private static class MemoryStore implements ScannerSourceRegistry.Store {
        final Map<String, Integer> values = new HashMap<>();
        boolean fail;
        public int read(String key) { Integer value = values.get(key); return value == null ? 0 : value; }
        public boolean write(String key, int type) { if (fail) return false; values.put(key, type); return true; }
    }
}
