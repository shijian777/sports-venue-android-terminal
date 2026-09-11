package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class Rk3288SerialSelectorTest {
    @Test
    public void api25NeverCallsApi26AndUsesLegacySerialFirst() {
        FakeAccess access = new FakeAccess(25);
        access.api26 = "API26-SERIAL";
        access.legacy = "RK3288-LEGACY-0001";
        access.properties.put("ro.serialno", "RK3288-PROP-0001");

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertTrue(serial.isAvailable());
        assertEquals("RK3288-LEGACY-0001", serial.value());
        assertEquals(0, access.api26Calls);
        assertEquals(1, access.legacyCalls);
        assertTrue(access.propertyCalls.isEmpty());
    }

    @Test
    public void api26SerialWinsWithoutReadingLowerPrioritySources() {
        FakeAccess access = new FakeAccess(26);
        access.api26 = "RK3288-API26-0001";
        access.legacy = "RK3288-LEGACY-0001";

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertEquals("RK3288-API26-0001", serial.value());
        assertEquals(1, access.api26Calls);
        assertEquals(0, access.legacyCalls);
        assertTrue(access.propertyCalls.isEmpty());
    }

    @Test
    public void api26SecurityFailureFallsBackWithoutExposingTheFailure() {
        FakeAccess access = new FakeAccess(26);
        access.api26Failure = new SecurityException("sensitive permission detail");
        access.legacy = "RK3288-LEGACY-0002";

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertTrue(serial.isAvailable());
        assertEquals("RK3288-LEGACY-0002", serial.value());
        assertFalse(serial.diagnostic().contains("sensitive"));
    }

    @Test
    public void invalidCandidatesFallThroughInTheDocumentedPropertyOrder() {
        FakeAccess access = new FakeAccess(25);
        access.legacy = "unknown";
        access.properties.put("ro.serialno", "short");
        access.properties.put("ro.boot.serialno", "RK3288-BOOT-0003");

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertEquals("RK3288-BOOT-0003", serial.value());
        assertEquals(Arrays.asList("ro.serialno", "ro.boot.serialno"),
                access.propertyCalls);
    }

    @Test
    public void unknownControlNonAsciiShortAndLongValuesAreUnavailableAndMasked() {
        String[] invalid = {
                "unknown",
                "RK3288\nBAD",
                "RK3288-终端-0001",
                "1234567",
                repeat('X', 129)
        };
        for (String candidate : invalid) {
            FakeAccess access = new FakeAccess(25);
            access.legacy = candidate;

            DeviceSerial serial = new Rk3288SerialSelector().select(access);

            assertFalse(candidate, serial.isAvailable());
            assertNull(candidate, serial.value());
            assertTrue(candidate, serial.diagnostic().length() > 0);
            assertEquals(candidate, repeat('*', serial.diagnostic().length()),
                    serial.diagnostic());
            assertFalse(candidate, serial.diagnostic().contains(candidate));
        }
    }

    @Test
    public void validDiagnosticRevealsOnlyTheFinalFourCharacters() {
        FakeAccess access = new FakeAccess(25);
        access.legacy = "RK3288-TERMINAL-1234";

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertEquals("****************1234", serial.diagnostic());
        assertFalse(serial.diagnostic().contains("RK3288"));
    }

    @Test
    public void missingAndThrowingSourcesEndInAStableUnavailableValue() {
        FakeAccess access = new FakeAccess(26);
        access.api26Failure = new IllegalStateException("api26");
        access.legacyFailure = new SecurityException("legacy");
        access.propertyFailure = new IllegalStateException("property");

        DeviceSerial serial = new Rk3288SerialSelector().select(access);

        assertFalse(serial.isAvailable());
        assertNull(serial.value());
        assertEquals("********", serial.diagnostic());
        assertEquals(Arrays.asList("ro.serialno", "ro.boot.serialno"),
                access.propertyCalls);
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static final class FakeAccess implements HardwareSerialAccess {
        final int sdkInt;
        final java.util.Map<String, String> properties = new java.util.HashMap<>();
        final List<String> propertyCalls = new ArrayList<>();
        String api26;
        String legacy;
        RuntimeException api26Failure;
        RuntimeException legacyFailure;
        RuntimeException propertyFailure;
        int api26Calls;
        int legacyCalls;

        FakeAccess(int sdkInt) {
            this.sdkInt = sdkInt;
        }

        @Override
        public int sdkInt() {
            return sdkInt;
        }

        @Override
        public String api26Serial() {
            api26Calls++;
            if (api26Failure != null) throw api26Failure;
            return api26;
        }

        @Override
        public String legacySerial() {
            legacyCalls++;
            if (legacyFailure != null) throw legacyFailure;
            return legacy;
        }

        @Override
        public String systemProperty(String name) {
            propertyCalls.add(name);
            if (propertyFailure != null) throw propertyFailure;
            return properties.get(name);
        }
    }
}
