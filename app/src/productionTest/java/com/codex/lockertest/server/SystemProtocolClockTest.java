package com.codex.lockertest.server;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SystemProtocolClockTest {
    @Test
    public void returnsCurrentSecondsAndAsiaShanghaiSigningDate() {
        long before = System.currentTimeMillis();
        ProtocolTimestamp timestamp = new SystemProtocolClock().now();
        long after = System.currentTimeMillis();

        assertTrue(timestamp.epochSeconds() >= before / 1000L);
        assertTrue(timestamp.epochSeconds() <= after / 1000L);

        Calendar expected = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        expected.setTimeInMillis(timestamp.epochSeconds() * 1000L);
        assertEquals(expected.get(Calendar.YEAR) + "/"
                        + (expected.get(Calendar.MONTH) + 1) + "/"
                        + expected.get(Calendar.DAY_OF_MONTH),
                timestamp.date().fullYear());
    }
}
