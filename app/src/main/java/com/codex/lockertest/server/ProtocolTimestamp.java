package com.codex.lockertest.server;

import java.util.TimeZone;

/** A seconds timestamp paired with its signing date in the caller-supplied zone. */
public final class ProtocolTimestamp {
    public static final long MIN_EPOCH_SECONDS = 946684800L;
    public static final long MAX_EPOCH_SECONDS = 4102444799L;

    private static final long MIN_EPOCH_MILLIS = MIN_EPOCH_SECONDS * 1000L;
    private static final long MAX_EPOCH_MILLIS = MAX_EPOCH_SECONDS * 1000L + 999L;

    private final long epochSeconds;
    private final ProtocolDate date;

    private ProtocolTimestamp(long epochSeconds, ProtocolDate date) {
        this.epochSeconds = epochSeconds;
        this.date = date;
    }

    public static ProtocolTimestamp fromEpochMillis(long epochMillis, TimeZone timeZone) {
        if (timeZone == null
                || epochMillis < MIN_EPOCH_MILLIS || epochMillis > MAX_EPOCH_MILLIS) {
            throw new ClockException();
        }
        long epochSeconds = epochMillis / 1000L;
        if (epochSeconds < MIN_EPOCH_SECONDS || epochSeconds > MAX_EPOCH_SECONDS) {
            throw new ClockException();
        }
        return new ProtocolTimestamp(
                epochSeconds, ProtocolDate.fromEpochMillis(epochMillis, timeZone));
    }

    public long epochSeconds() {
        return epochSeconds;
    }

    public ProtocolDate date() {
        return date;
    }

    public static final class ClockException extends RuntimeException {
        private ClockException() {
            super("Invalid protocol clock");
        }

        public String code() {
            return "CLOCK_INVALID";
        }
    }
}
