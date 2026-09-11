package com.codex.lockertest.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Pure formatter for the reference kiosk clock. */
public final class ZipClockText {
    private ZipClockText() {
    }

    public static String format(long epochMillis, TimeZone timeZone) {
        if (timeZone == null) {
            throw new IllegalArgumentException("timeZone must not be null");
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.US);
        format.setTimeZone(timeZone);
        return format.format(new Date(epochMillis));
    }
}
