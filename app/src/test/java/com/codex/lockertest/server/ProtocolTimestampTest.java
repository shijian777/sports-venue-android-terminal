package com.codex.lockertest.server;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class ProtocolTimestampTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Test
    public void acceptsEveryMillisecondInsideTheInclusiveSecondBounds() {
        assertEquals(946684800L,
                ProtocolTimestamp.fromEpochMillis(946684800000L, SHANGHAI).epochSeconds());
        assertEquals(4102444799L,
                ProtocolTimestamp.fromEpochMillis(4102444799999L, SHANGHAI).epochSeconds());
        assertEquals(946684800L, ProtocolTimestamp.MIN_EPOCH_SECONDS);
        assertEquals(4102444799L, ProtocolTimestamp.MAX_EPOCH_SECONDS);
    }

    @Test
    public void rejectsMillisecondsImmediatelyOutsideBoundsBeforeDivision() {
        assertClockInvalid(946684799999L);
        assertClockInvalid(4102444800000L);
        assertClockInvalid(0L);
        assertClockInvalid(-1L);
    }

    @Test
    public void suppliedInstantAndZoneDetermineTheUnpaddedProtocolDates() {
        ProtocolTimestamp before =
                ProtocolTimestamp.fromEpochMillis(1788364799000L, SHANGHAI);
        ProtocolTimestamp after =
                ProtocolTimestamp.fromEpochMillis(1788364800000L, SHANGHAI);
        ProtocolTimestamp shortYear =
                ProtocolTimestamp.fromEpochMillis(1136131200000L, SHANGHAI);

        assertEquals("2026/9/2", before.date().fullYear());
        assertEquals("26/9/2", before.date().shortYear());
        assertEquals("2026/9/3", after.date().fullYear());
        assertEquals("26/9/3", after.date().shortYear());
        assertEquals("2006/1/2", shortYear.date().fullYear());
        assertEquals("06/1/2", shortYear.date().shortYear());
    }

    @Test
    public void dateUsesTheExactSuppliedTimeZoneRatherThanTheHostDefault() {
        long instant = 1788364800000L;
        ProtocolTimestamp utc = ProtocolTimestamp.fromEpochMillis(
                instant, TimeZone.getTimeZone("UTC"));
        ProtocolTimestamp shanghai = ProtocolTimestamp.fromEpochMillis(instant, SHANGHAI);

        assertEquals("2026/9/2", utc.date().fullYear());
        assertEquals("2026/9/3", shanghai.date().fullYear());
    }

    @Test
    public void invalidZoneAndClockErrorsNeverEchoValues() {
        try {
            ProtocolTimestamp.fromEpochMillis(1788364800000L, null);
            fail("expected invalid clock");
        } catch (ProtocolTimestamp.ClockException expected) {
            assertEquals("CLOCK_INVALID", expected.code());
            assertEquals("Invalid protocol clock", expected.getMessage());
            assertFalse(expected.toString().contains("1788364800000"));
        }
    }

    @Test
    public void protocolClockExposesOnlyNow() {
        Method[] methods = ProtocolClock.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("now", methods[0].getName());
        assertEquals(ProtocolTimestamp.class, methods[0].getReturnType());
        assertEquals(0, methods[0].getParameterTypes().length);
    }

    private static void assertClockInvalid(long millis) {
        try {
            ProtocolTimestamp.fromEpochMillis(millis, SHANGHAI);
            fail("expected invalid clock");
        } catch (ProtocolTimestamp.ClockException expected) {
            assertEquals("CLOCK_INVALID", expected.code());
            assertEquals("Invalid protocol clock", expected.getMessage());
            assertFalse(expected.getMessage().contains(Long.toString(millis)));
        }
    }
}
