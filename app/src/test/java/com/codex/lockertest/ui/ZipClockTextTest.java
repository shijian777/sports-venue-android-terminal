package com.codex.lockertest.ui;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public final class ZipClockTextTest {
    @Test
    public void formatsReferenceStyleChineseKioskTimestampIncludingSeconds() {
        TimeZone shanghai = TimeZone.getTimeZone("Asia/Shanghai");
        Calendar calendar = Calendar.getInstance(shanghai);
        calendar.clear();
        calendar.set(2025, Calendar.DECEMBER, 25, 12, 0, 9);

        assertEquals("2025/12/25  12:00:09",
                ZipClockText.format(calendar.getTimeInMillis(), shanghai));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimeZone() {
        ZipClockText.format(0L, null);
    }
}
