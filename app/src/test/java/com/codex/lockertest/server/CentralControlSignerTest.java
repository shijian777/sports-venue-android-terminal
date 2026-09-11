package com.codex.lockertest.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CentralControlSignerTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    @Test
    public void matchesCheckDeviceFakeGoldenVector() {
        CentralControlSigner.Result result = CentralControlSigner.sign(
                CentralControlData.fromRawJson(
                        "[{\"device_serial\":\"RK3288-TEST-001\"}]"),
                ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI),
                "TEST_ONLY_SYS_CODE".toCharArray());

        assertEquals("98d3e75e0d2d662e0847abd8fd02e161", result.scode());
        assertEquals("ca61c4a02092d1ec939c9710f3433819", result.sign());
    }

    @Test
    public void matchesEmptyArrayFakeGoldenVector() {
        CentralControlSigner.Result result = CentralControlSigner.sign(
                CentralControlData.fromRawJson("[]"),
                ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI),
                "TEST_ONLY_SYS_CODE".toCharArray());

        assertEquals("d751713988987e9331980363e24189ce", result.scode());
        assertEquals("02895f1e71149b8377237dabadf98ae9", result.sign());
    }

    @Test
    public void stringModeHashesTheUnquotedOriginalText() {
        CentralControlSigner.Result result = CentralControlSigner.sign(
                CentralControlData.fromString("abc"),
                ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI),
                "TEST_ONLY_SYS_CODE".toCharArray());

        assertEquals("900150983cd24fb0d6963f7d28e17f72", result.scode());
    }

    @Test
    public void callerOwnedKeyIsUnchangedOnSuccessAndFailure() {
        char[] successKey = "TEST_ONLY_SYS_CODE".toCharArray();
        char[] successCopy = Arrays.copyOf(successKey, successKey.length);
        CentralControlSigner.sign(
                CentralControlData.fromRawJson("[]"),
                ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI), successKey);
        assertArrayEquals(successCopy, successKey);

        char[] invalidKey = new char[] {'T', '\uD800'};
        char[] invalidCopy = Arrays.copyOf(invalidKey, invalidKey.length);
        try {
            CentralControlSigner.sign(
                    CentralControlData.fromRawJson("[]"),
                    ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI), invalidKey);
            fail("expected invalid signing key");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid signing input", expected.getMessage());
        }
        assertArrayEquals(invalidCopy, invalidKey);
    }

    @Test
    public void signingErrorsAreFixedAndDoNotEchoInput() {
        char[] sentinel = "SENSITIVE_INPUT_SENTINEL".toCharArray();
        try {
            CentralControlSigner.sign(null,
                    ProtocolTimestamp.fromEpochMillis(1788408000000L, SHANGHAI), sentinel);
            fail("expected invalid signing input");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid signing input", expected.getMessage());
            assertFalse(expected.toString().contains("SENSITIVE_INPUT_SENTINEL"));
        }
        assertArrayEquals("SENSITIVE_INPUT_SENTINEL".toCharArray(), sentinel);
    }

    @Test
    public void sourceContractClearsLocalArraysAndHasNoCredentialOrDigestSeam()
            throws Exception {
        String source = new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/codex/lockertest/server/CentralControlSigner.java")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("finally"));
        assertTrue(source.contains("clear(keyCopy)"));
        assertTrue(source.contains("clear(keyBytes)"));
        assertTrue(source.contains("clear(digestBytes)"));
        assertTrue(source.contains("Arrays.fill(value, (byte) 0)"));
        assertTrue(source.contains("Arrays.fill(value, '\\0')"));
        assertFalse(source.contains("new String(sysCode"));
        assertFalse(source.contains("ProductionSecretProvider"));
        assertFalse(source.contains("System.getenv"));
        assertFalse(source.contains("MessageDigest digest,"));
        assertEquals(0, CentralControlSigner.class.getDeclaredFields().length);
    }

    private static Path projectRoot() {
        return Paths.get(System.getProperty("codex.projectRoot", "."))
                .toAbsolutePath().normalize();
    }
}
