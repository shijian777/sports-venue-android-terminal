package com.codex.lockertest.server;

/** Supplies one validated protocol timestamp per request. */
public interface ProtocolClock {
    ProtocolTimestamp now();
}
