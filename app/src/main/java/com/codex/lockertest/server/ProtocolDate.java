package com.codex.lockertest.server;

import java.util.Calendar;
import java.util.TimeZone;

/** The two unpadded date forms consumed by the central-control signature. */
public final class ProtocolDate {
    private final int year;
    private final int month;
    private final int day;

    private ProtocolDate(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    static ProtocolDate fromEpochMillis(long epochMillis, TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance((TimeZone) timeZone.clone());
        calendar.setTimeInMillis(epochMillis);
        return new ProtocolDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    public String fullYear() {
        return Integer.toString(year) + '/' + month + '/' + day;
    }

    public String shortYear() {
        int shortYear = year % 100;
        StringBuilder result = new StringBuilder(7);
        if (shortYear < 10) result.append('0');
        result.append(shortYear).append('/').append(month).append('/').append(day);
        return result.toString();
    }
}
