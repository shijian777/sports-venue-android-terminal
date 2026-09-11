package com.codex.lockertest.server;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class CentralControlEnvelopeFactoryTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ProtocolTimestamp TIMESTAMP =
            ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI);

    @Test
    public void stringDataSignsOriginalButEmitsEscapedJsonString() {
        String original = "a\"\\\n\uD834\uDD1E";
        CentralControlData data = CentralControlData.fromString(original);

        assertEquals(original, data.signingText());
        assertEquals("\"a\\\"\\\\\\n\uD834\uDD1E\"", data.wireJson());
        assertEquals("e20f112cca9855208cf4220d0d3731f7",
                CentralControlSigner.sign(data, TIMESTAMP,
                        "TEST_ONLY_SYS_CODE".toCharArray()).scode());
    }

    @Test
    public void rawJsonIsValidatedOnceAndReusedByteForByte() {
        String raw = " \r\n { \"n\" : 2e2, \"text\" : \"x\" } \t";
        CentralControlData data = CentralControlData.fromRawJson(raw);

        assertEquals(raw, data.signingText());
        assertEquals(raw, data.wireJson());
        assertEquals(raw, data.signingText());
        assertEquals(raw, data.wireJson());
    }

    @Test
    public void rawJsonStringModeRemainsDistinctFromStringValueMode() {
        CentralControlData raw = CentralControlData.fromRawJson("\"abc\"");
        CentralControlData string = CentralControlData.fromString("\"abc\"");

        assertEquals("\"abc\"", raw.signingText());
        assertEquals("\"abc\"", raw.wireJson());
        assertEquals("\"abc\"", string.signingText());
        assertEquals("\"\\\"abc\\\"\"", string.wireJson());
    }

    @Test
    public void invalidDataUsesFixedNonEchoingErrors() {
        String sentinel = "SENSITIVE_INPUT_SENTINEL";
        try {
            CentralControlData.fromRawJson("{\"" + sentinel + "\":}");
            fail("expected invalid raw JSON");
        } catch (JsonContractException expected) {
            assertEquals("INVALID_JSON", expected.code());
            assertFalse(expected.toString().contains(sentinel));
        }
        try {
            CentralControlData.fromString(null);
            fail("expected invalid data");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid central-control data", expected.getMessage());
        }
    }

    @Test
    public void nullTokenIsAbsentAndFieldsUseExactCompactOrder() {
        CentralControlData data = CentralControlData.fromRawJson(
                "[{\"device_serial\":\"RK3288-TEST-001\"}]");
        CentralControlSigner.Result signature = CentralControlSigner.sign(
                data, TIMESTAMP, "TEST_ONLY_SYS_CODE".toCharArray());

        assertEquals(
                "{\"scode\":\"98d3e75e0d2d662e0847abd8fd02e161\","
                        + "\"sign\":\"ca61c4a02092d1ec939c9710f3433819\","
                        + "\"timestamp\":1788408000,\"data\":[{\"device_serial\":"
                        + "\"RK3288-TEST-001\"}]}",
                CentralControlEnvelopeFactory.create(signature, TIMESTAMP, null, data));
    }

    @Test
    public void emptyAndNonEmptyTokensArePresentAndJsonEscaped() {
        CentralControlData data = CentralControlData.fromRawJson("[]");
        CentralControlSigner.Result signature = CentralControlSigner.sign(
                data, TIMESTAMP, "TEST_ONLY_SYS_CODE".toCharArray());

        assertEquals(
                "{\"scode\":\"d751713988987e9331980363e24189ce\","
                        + "\"sign\":\"02895f1e71149b8377237dabadf98ae9\","
                        + "\"timestamp\":1788408000,\"token\":\"\",\"data\":[]}",
                CentralControlEnvelopeFactory.create(signature, TIMESTAMP, "", data));
        assertEquals(
                "{\"scode\":\"d751713988987e9331980363e24189ce\","
                        + "\"sign\":\"02895f1e71149b8377237dabadf98ae9\","
                        + "\"timestamp\":1788408000,\"token\":\"a\\\"\\\\\\n\","
                        + "\"data\":[]}",
                CentralControlEnvelopeFactory.create(signature, TIMESTAMP, "a\"\\\n", data));
    }

    @Test
    public void envelopeReusesRawDataTextWithoutParsingOrReserializing() {
        CentralControlData data = CentralControlData.fromRawJson(" [ ] ");
        CentralControlSigner.Result signature = CentralControlSigner.sign(
                data, TIMESTAMP, "TEST_ONLY_SYS_CODE".toCharArray());
        String envelope = CentralControlEnvelopeFactory.create(signature, TIMESTAMP, null, data);

        assertEquals(" [ ] ", data.wireJson());
        assertEquals(" [ ] }", envelope.substring(envelope.length() - 6));
    }
}
