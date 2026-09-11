package com.codex.lockertest.server;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public final class ProductionSecretProviderTest {
    @Test
    public void everyCopyIsASeparateUsableCredential() {
        char[] first = ProductionSecretProvider.copyOrEmpty();
        char[] second = ProductionSecretProvider.copyOrEmpty();

        try {
            assertEquals(16, first.length);
            assertEquals(16, second.length);
            assertNotSame(first, second);
            assertArrayEquals(first, second);

            first[0] = '\0';
            assertTrue(second[0] != '\0');
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
        }
    }

    @Test
    public void documentedCredentialProducesThePinnedCheckDeviceSignature() {
        char[] credential = ProductionSecretProvider.copyOrEmpty();
        try {
            ProtocolTimestamp timestamp = ProtocolTimestamp.fromEpochMillis(
                    1_788_408_000_000L,
                    TimeZone.getTimeZone("Asia/Shanghai"));
            CentralControlData data = CentralControlData.fromRawJson(
                    "[{\"device_serial\":\"76d9b6ca56a3a61a\"}]");

            CentralControlSigner.Result result = CentralControlSigner.sign(
                    data, timestamp, credential);

            assertEquals("bc990599403d92e4eb951751781fbd76", result.scode());
            assertEquals("73288a714eb840299fb1af80ce5a1b53", result.sign());
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    @Test
    public void providerHasNoStateOrInjectionSeam() throws Exception {
        assertEquals(0, ProductionSecretProvider.class.getDeclaredFields().length);

        Constructor<?>[] constructors =
                ProductionSecretProvider.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(0, constructors[0].getParameterTypes().length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));

        Method[] methods = ProductionSecretProvider.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("copyOrEmpty", methods[0].getName());
        assertEquals(char[].class, methods[0].getReturnType());
        assertEquals(0, methods[0].getParameterTypes().length);
        assertTrue(Modifier.isPublic(methods[0].getModifiers()));
        assertTrue(Modifier.isStatic(methods[0].getModifiers()));
    }
}
