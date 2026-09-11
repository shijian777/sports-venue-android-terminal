package com.codex.lockertest.business.mqtt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class MqttSettingsTest {
    @Test
    public void rejectsMissingHost() {
        reject(null, 8883, "terminal-1", "", "", "locker/terminal-1");
    }

    @Test
    public void rejectsMalformedDnsIpv4AndNetworkLocationSyntax() {
        String longLabel = repeat('a', 64);
        String longHost = repeat('a', 63) + "." + repeat('b', 63) + "."
                + repeat('c', 63) + "." + repeat('d', 62) + ".e";
        String[] invalid = {"", " broker.example", "broker example", "broker.example ",
                "mqtts://broker.example", "broker.example/path", "broker.example:8883",
                "broker..example", "-broker.example", "broker-.example", "brøker.example",
                longLabel + ".example", longHost, "256.1.1.1", "1.2.3"};
        for (String host : invalid) {
            reject(host, 8883, "terminal-1", "", "", "locker/terminal-1");
        }
    }

    @Test
    public void acceptsAsciiDnsAndIpv4Hosts() {
        assertEquals("broker-1.example.com", valid("broker-1.example.com").host());
        assertEquals("192.168.10.25", valid("192.168.10.25").host());
    }

    @Test
    public void rejectsPortsOutsideUnsignedMqttRange() {
        reject("broker.example", 0, "terminal-1", "", "", "locker/terminal-1");
        reject("broker.example", 65536, "terminal-1", "", "", "locker/terminal-1");
    }

    @Test
    public void rejectsMissingOrOversizedClientIdAndReservedTopicWildcards() {
        reject("broker.example", 8883, "", "", "", "locker/terminal-1");
        reject("broker.example", 8883, repeat('x', 129), "", "", "locker/terminal-1");
        reject("broker.example", 8883, "terminal-1", "", "", "");
        reject("broker.example", 8883, "terminal-1", "", "", "locker/+");
        reject("broker.example", 8883, "terminal-1", "", "", "locker/#");
        reject("broker.example", 8883, "terminal-1", "", "", repeat('t', 513));
    }

    @Test
    public void measuresOpaqueFieldLimitsInStrictUtf8Bytes() {
        String emoji = "\ud83d\ude80";
        new MqttSettings("broker.example", 8883, repeat(emoji, 32),
                repeat(emoji, 64), repeat(emoji, 1024), repeat(emoji, 128));
        reject("broker.example", 8883, repeat(emoji, 33), "", "", "topic");
        reject("broker.example", 8883, "id", repeat(emoji, 65), "", "topic");
        reject("broker.example", 8883, "id", "user", repeat(emoji, 1025), "topic");
        reject("broker.example", 8883, "id", "", "", repeat(emoji, 129));
    }

    @Test
    public void rejectsNullControlAndUnpairedSurrogateOpaqueFields() {
        reject("broker.example", 8883, null, "", "", "topic");
        reject("broker.example", 8883, "id", null, "", "topic");
        reject("broker.example", 8883, "id", "", null, "topic");
        reject("broker.example", 8883, "id", "", "", null);
        reject("broker.example", 8883, "id\n", "", "", "topic");
        reject("broker.example", 8883, "id", "us\u0000er", "", "topic");
        reject("broker.example", 8883, "id", "user", "pa\u007fss", "topic");
        reject("broker.example", 8883, "id", "", "", "to\u0085pic");
        reject("broker.example", 8883, "bad\ud800", "", "", "topic");
        reject("broker.example", 8883, "id", "bad\udc00", "", "topic");
    }

    @Test
    public void passwordRequiresUsername() {
        reject("broker.example", 8883, "terminal-1", "", "secret", "locker/terminal-1");
    }

    @Test
    public void preservesOpaqueValuesWithoutTrimmingOrSecretStringification() {
        MqttSettings settings = new MqttSettings("broker.example", 8883, "007-client",
                " 007-user ", " 007-password ", " 007/topic ");
        assertEquals("007-client", settings.clientId());
        assertEquals(" 007-user ", settings.username());
        assertEquals(" 007-password ", settings.password());
        assertEquals(" 007/topic ", settings.topic());
        assertFalse(settings.toString().contains("007-password"));
    }

    private static MqttSettings valid(String host) {
        return new MqttSettings(host, 8883, "terminal-1", "", "", "locker/terminal-1");
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void reject(String host, int port, String clientId,
                               String username, String password, String topic) {
        try {
            new MqttSettings(host, port, clientId, username, password, topic);
            fail("expected invalid MQTT settings to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: construction is the validation boundary.
        }
    }
}
