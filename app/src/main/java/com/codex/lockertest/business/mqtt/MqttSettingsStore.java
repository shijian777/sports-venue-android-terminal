package com.codex.lockertest.business.mqtt;

/** Persistence boundary for administrator-approved MQTT settings. */
public interface MqttSettingsStore {
    MqttSettings load() throws Exception;

    void save(MqttSettings settings) throws Exception;
}
