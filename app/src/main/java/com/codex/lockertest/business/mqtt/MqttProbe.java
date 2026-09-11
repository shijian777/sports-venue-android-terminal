package com.codex.lockertest.business.mqtt;

import com.codex.lockertest.server.CallToken;

/** Explicit, connect-only MQTT diagnostic. Implementations must not publish or subscribe. */
public interface MqttProbe {
    enum Outcome {
        CONNECTED,
        AUTH_REJECTED,
        UNSUPPORTED,
        SERVER_UNAVAILABLE,
        TLS_FAILED,
        NETWORK_FAILED,
        TIMEOUT,
        CANCELLED,
        INVALID_RESPONSE
    }

    Outcome test(MqttSettings settings, CallToken token);
}
