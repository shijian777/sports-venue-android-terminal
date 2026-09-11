package com.codex.lockertest.server;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ProductionServerConfigTest {
    @Test
    public void productionConfigExposesOnlyTheApprovedNonSecretLimits() {
        assertEquals("https://devyoga.gmtfit.com", ProductionServerConfig.BASE_URL);
        assertEquals(5_000, ProductionServerConfig.CONNECT_TIMEOUT_MILLIS);
        assertEquals(10_000, ProductionServerConfig.READ_TIMEOUT_MILLIS);
        assertEquals(15_000, ProductionServerConfig.REQUEST_TIMEOUT_MILLIS);
        assertEquals(1_048_576, ProductionServerConfig.MAX_RESPONSE_BODY_BYTES);

        Set<String> expectedNames = new HashSet<>(Arrays.asList(
                "BASE_URL",
                "CONNECT_TIMEOUT_MILLIS",
                "READ_TIMEOUT_MILLIS",
                "REQUEST_TIMEOUT_MILLIS",
                "MAX_RESPONSE_BODY_BYTES"));
        Field[] fields = ProductionServerConfig.class.getDeclaredFields();
        Set<String> actualNames = new HashSet<>();
        for (Field field : fields) {
            actualNames.add(field.getName());
            int modifiers = field.getModifiers();
            assertTrue(field.getName(), Modifier.isPublic(modifiers));
            assertTrue(field.getName(), Modifier.isStatic(modifiers));
            assertTrue(field.getName(), Modifier.isFinal(modifiers));
        }
        assertEquals(expectedNames, actualNames);
    }
}
