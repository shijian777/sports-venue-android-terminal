package com.codex.lockertest.server;

import java.util.TimeZone;

/** API-25-compatible wall clock for the central-control signing protocol. */
public final class SystemProtocolClock implements ProtocolClock {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Override
    public ProtocolTimestamp now() {
        return ProtocolTimestamp.fromEpochMillis(
                System.currentTimeMillis(), SHANGHAI);
    }
}
