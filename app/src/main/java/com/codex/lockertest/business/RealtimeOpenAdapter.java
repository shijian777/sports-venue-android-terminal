package com.codex.lockertest.business;

/** Deliberately inert MQTT extension boundary until a broker/message contract is supplied. */
public interface RealtimeOpenAdapter {
    String unavailableReason();

    static RealtimeOpenAdapter unconfigured() {
        return new RealtimeOpenAdapter() {
            @Override public String unavailableReason() {
                return "MQTT连接与消息协议未配置";
            }
        };
    }
}
