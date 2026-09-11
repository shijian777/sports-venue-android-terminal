package com.codex.lockertest.server;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BootstrapHeadersTest {
    private static final String MERCHANT = "gmt-merchant-auth";
    private static final String DEVICE = "device-no";

    @Test
    public void acceptsOnlyEmptyOrTheExactLowercasePair() {
        BootstrapHeaders empty = BootstrapHeaders.from(Collections.emptyMap());
        assertTrue(empty.isEmpty());
        assertEquals(Collections.emptyMap(), empty.asMap());

        Map<String, String> values = validPair("MERCHANT 01", "DEVICE-01");
        BootstrapHeaders bound = BootstrapHeaders.from(values);
        assertFalse(bound.isEmpty());
        assertEquals(2, bound.asMap().size());
        assertEquals("MERCHANT 01", bound.asMap().get(MERCHANT));
        assertEquals("DEVICE-01", bound.asMap().get(DEVICE));
    }

    @Test
    public void acceptsValueLengthBoundariesAndInternalAsciiSpace() {
        assertAccepted("A");
        assertAccepted(repeat('Z', 256));
        assertAccepted("A B");
    }

    @Test
    public void rejectsEmptyOverlongAndEdgeWhitespaceValues() {
        assertRejectedValue("");
        assertRejectedValue(repeat('Z', 257));
        assertRejectedValue(" leading");
        assertRejectedValue("trailing ");
        assertRejectedValue(" ");
    }

    @Test
    public void rejectsEveryC0CharacterDelAndNonAscii() {
        for (int value = 0; value <= 0x1f; value++) {
            assertRejectedValue("A" + (char) value + "B");
        }
        assertRejectedValue("A" + (char) 0x7f + "B");
        assertRejectedValue("A" + (char) 0x80 + "B");
    }

    @Test
    public void rejectsMissingUnknownAliasedDuplicatedAndStandardHeaders() {
        assertRejectedMap(singleton(MERCHANT, "M"));
        assertRejectedMap(singleton(DEVICE, "D"));
        assertRejectedMap(singleton("unknown", "X"));
        assertRejectedMap(singleton("gmt_merchant_auth", "M"));
        assertRejectedMap(singleton("device_no", "D"));
        assertRejectedMap(singleton("Gmt-Merchant-Auth", "M"));
        assertRejectedMap(singleton("Device-No", "D"));
        assertRejectedMap(singleton("Content-Type", "application/json"));
        assertRejectedMap(singleton("Accept", "application/json"));

        Map<String, String> caseDuplicate = validPair("M", "D");
        caseDuplicate.put("GMT-MERCHANT-AUTH", "M2");
        assertRejectedMap(caseDuplicate);
    }

    @Test
    public void rejectsNullMapKeysAndValues() {
        assertRejectedMap(null);

        Map<String, String> nullKey = validPair("M", "D");
        nullKey.put(null, "X");
        assertRejectedMap(nullKey);

        Map<String, String> nullValue = validPair("M", "D");
        nullValue.put(DEVICE, null);
        assertRejectedMap(nullValue);
    }

    @Test
    public void copiesInputAndExposesAnImmutableView() {
        Map<String, String> source = validPair("M", "D");
        BootstrapHeaders headers = BootstrapHeaders.from(source);

        source.put(MERCHANT, "CHANGED");
        source.clear();
        assertEquals("M", headers.asMap().get(MERCHANT));
        assertEquals("D", headers.asMap().get(DEVICE));

        try {
            headers.asMap().put(MERCHANT, "M2");
            fail("Expected immutable header view");
        } catch (UnsupportedOperationException expected) {
            assertEquals("M", headers.asMap().get(MERCHANT));
        }
    }

    @Test
    public void convenienceFactoriesRetainTheSameValidation() {
        assertTrue(BootstrapHeaders.none().isEmpty());
        assertEquals(validPair("M", "D"), BootstrapHeaders.bound("M", "D").asMap());

        try {
            BootstrapHeaders.bound("M", " bad");
            fail("Expected invalid convenience value to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains(" bad"));
        }
    }

    @Test
    public void requiredConstructionAndReadMethodsArePublic() throws Exception {
        assertPublicStatic(BootstrapHeaders.class.getMethod("none"));
        assertPublicStatic(BootstrapHeaders.class.getMethod(
                "bound", String.class, String.class));
        assertPublicStatic(BootstrapHeaders.class.getMethod("from", Map.class));
        assertTrue(Modifier.isPublic(
                BootstrapHeaders.class.getMethod("isEmpty").getModifiers()));
        assertTrue(Modifier.isPublic(
                BootstrapHeaders.class.getMethod("asMap").getModifiers()));
    }

    private static void assertAccepted(String value) {
        assertEquals(value, BootstrapHeaders.from(validPair(value, "D"))
                .asMap().get(MERCHANT));
    }

    private static void assertRejectedValue(String value) {
        assertRejectedMap(validPair(value, "D"));
        assertRejectedMap(validPair("M", value));
    }

    private static void assertRejectedMap(Map<String, String> values) {
        try {
            BootstrapHeaders.from(values);
            fail("Expected invalid bootstrap headers to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid bootstrap headers", expected.getMessage());
        }
    }

    private static void assertPublicStatic(Method method) {
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }

    private static Map<String, String> singleton(String name, String value) {
        Map<String, String> result = new HashMap<>();
        result.put(name, value);
        return result;
    }

    private static Map<String, String> validPair(String merchant, String device) {
        Map<String, String> result = new HashMap<>();
        result.put(MERCHANT, merchant);
        result.put(DEVICE, device);
        return result;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
