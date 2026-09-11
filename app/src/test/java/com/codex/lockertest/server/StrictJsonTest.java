package com.codex.lockertest.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class StrictJsonTest {
    @Test
    public void parseUsesOnlyApprovedTypesAndPreservesOrderAndNumberTokens() {
        Object parsed = StrictJson.parse(
                "{\"text\":\"ok\",\"numbers\":[0,-0,200.0,2e2,200e0,1.0e2],"
                        + "\"flag\":true,\"nothing\":null,\"object\":{\"x\":1}}");

        assertTrue(parsed instanceof LinkedHashMap);
        Map<?, ?> root = (Map<?, ?>) parsed;
        Iterator<?> keys = root.keySet().iterator();
        assertEquals("text", keys.next());
        assertEquals("numbers", keys.next());
        assertEquals("flag", keys.next());
        assertEquals("nothing", keys.next());
        assertEquals("object", keys.next());
        assertFalse(keys.hasNext());
        assertEquals("ok", root.get("text"));
        assertEquals(Boolean.TRUE, root.get("flag"));
        assertNull(root.get("nothing"));
        assertTrue(root.get("numbers") instanceof ArrayList);
        assertTrue(root.get("object") instanceof LinkedHashMap);

        List<?> numbers = (List<?>) root.get("numbers");
        assertEquals("0", number(numbers.get(0)).rawToken());
        assertEquals("-0", number(numbers.get(1)).rawToken());
        assertEquals("200.0", number(numbers.get(2)).rawToken());
        assertEquals("2e2", number(numbers.get(3)).rawToken());
        assertEquals("200e0", number(numbers.get(4)).rawToken());
        assertEquals("1.0e2", number(numbers.get(5)).rawToken());
    }

    @Test
    public void duplicateKeysAreRejectedAfterEscapeDecoding() {
        assertInvalid("{\"a\":1,\"\\u0061\":2}");
        assertInvalid("{\"music\\uD834\\uDD1E\":1,\"music\uD834\uDD1E\":2}");
    }

    @Test
    public void trailingTokensInvalidEscapesAndRawControlsAreRejected() {
        assertInvalid("{} []");
        assertInvalid("{\"x\":1,}");
        assertInvalid("[1,]");
        assertInvalid("\"\\x41\"");
        assertInvalid("\"line\nfeed\"");
        assertInvalid("\uFEFF{}");
        assertInvalid("");
        assertInvalid(" \t\r\n ");
        assertInvalid(null);
    }

    @Test
    public void isolatedSurrogatesAreRejectedAndPairsAreAccepted() {
        assertInvalid("\"\\uD834\"");
        assertInvalid("\"\\uDD1E\"");
        assertInvalid("\"\\uD834x\"");
        assertInvalid("\"" + '\uD834' + "\"");
        assertInvalid("\"" + '\uDD1E' + "\"");

        assertEquals("\uD834\uDD1E", StrictJson.parse("\"\\uD834\\uDD1E\""));
        assertEquals("\uD834\uDD1E", StrictJson.parse("\"\uD834\uDD1E\""));
    }

    @Test
    public void compositeDepthThirtyTwoIsAcceptedAndThirtyThreeIsRejected() {
        assertTrue(StrictJson.parse(nestedArrays(32)) instanceof ArrayList);
        assertInvalid(nestedArrays(33));
        assertTrue(StrictJson.parse(nestedObjects(32)) instanceof LinkedHashMap);
        assertInvalid(nestedObjects(33));
    }

    @Test
    public void illegalJsonNumberLexemesAreRejected() {
        String[] invalid = {
                "00", "0200", "-01", "+1", ".1", "1.", "1e", "1e+", "NaN",
                "[00]", "{\"n\":0200}", "[-01]"
        };
        for (String json : invalid) {
            assertInvalid(json);
        }
    }

    @Test
    public void fractionAndExponentTokensNeverBecomeCanonicalIntegers() {
        List<?> values = (List<?>) StrictJson.parse("[200.0,2e2,200e0,1.0e2]");
        for (Object value : values) {
            JsonNumber parsed = number(value);
            assertContractFailure("CANONICAL_INTEGER_REQUIRED", new ThrowingRunnable() {
                @Override
                public void run() {
                    parsed.toLong(Long.MIN_VALUE, Long.MAX_VALUE);
                }
            });
        }
    }

    @Test
    public void canonicalIntegerHelpersUseOverflowSafeExactBoundaries() {
        assertEquals(Integer.MIN_VALUE,
                number(StrictJson.parse("-2147483648")).toInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE,
                number(StrictJson.parse("2147483647")).toInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(Long.MIN_VALUE,
                number(StrictJson.parse("-9223372036854775808"))
                        .toLong(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE,
                number(StrictJson.parse("9223372036854775807"))
                        .toLong(Long.MIN_VALUE, Long.MAX_VALUE));

        assertRangeFailure("2147483648", true);
        assertRangeFailure("-2147483649", true);
        assertRangeFailure("9223372036854775808", false);
        assertRangeFailure("-9223372036854775809", false);
        assertContractFailure("CANONICAL_INTEGER_REQUIRED", new ThrowingRunnable() {
            @Override
            public void run() {
                number(StrictJson.parse("-0")).toInt(-1, 1);
            }
        });
    }

    @Test
    public void canonicalIntegerHelpersEnforceExplicitFieldRanges() {
        assertEquals(0, number(StrictJson.parse("0")).toInt(0, 1_000_000));
        assertEquals(1_000_000,
                number(StrictJson.parse("1000000")).toInt(0, 1_000_000));
        assertContractFailure("INTEGER_OUT_OF_RANGE", new ThrowingRunnable() {
            @Override
            public void run() {
                number(StrictJson.parse("-1")).toInt(0, 1_000_000);
            }
        });
        assertContractFailure("INTEGER_OUT_OF_RANGE", new ThrowingRunnable() {
            @Override
            public void run() {
                number(StrictJson.parse("1000001")).toInt(0, 1_000_000);
            }
        });
        assertContractFailure("INVALID_RANGE", new ThrowingRunnable() {
            @Override
            public void run() {
                number(StrictJson.parse("1")).toLong(2, 1);
            }
        });
    }

    @Test
    public void failuresUseFixedCodesAndNeverEchoInput() {
        String sentinel = "SENSITIVE_INPUT_SENTINEL";
        try {
            StrictJson.parse("{\"" + sentinel + "\":}");
            fail("expected invalid JSON");
        } catch (JsonContractException expected) {
            assertEquals("INVALID_JSON", expected.code());
            assertEquals("Invalid JSON", expected.getMessage());
            assertFalse(expected.getMessage().contains(sentinel));
            assertFalse(expected.toString().contains(sentinel));
        }
    }

    private static void assertRangeFailure(final String token, final boolean asInt) {
        assertContractFailure("INTEGER_OUT_OF_RANGE", new ThrowingRunnable() {
            @Override
            public void run() {
                JsonNumber value = number(StrictJson.parse(token));
                if (asInt) {
                    value.toInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
                } else {
                    value.toLong(Long.MIN_VALUE, Long.MAX_VALUE);
                }
            }
        });
    }

    private static void assertInvalid(String json) {
        try {
            StrictJson.parse(json);
            fail("expected invalid JSON");
        } catch (JsonContractException expected) {
            assertEquals("INVALID_JSON", expected.code());
            assertEquals("Invalid JSON", expected.getMessage());
        }
    }

    private static void assertContractFailure(String code, ThrowingRunnable action) {
        try {
            action.run();
            fail("expected contract failure");
        } catch (JsonContractException expected) {
            assertEquals(code, expected.code());
            assertFalse(expected.getMessage().contains("SENSITIVE_INPUT_SENTINEL"));
        }
    }

    private static JsonNumber number(Object value) {
        assertTrue(value instanceof JsonNumber);
        return (JsonNumber) value;
    }

    private static String nestedArrays(int depth) {
        StringBuilder json = new StringBuilder();
        for (int index = 0; index < depth; index++) json.append('[');
        json.append('0');
        for (int index = 0; index < depth; index++) json.append(']');
        return json.toString();
    }

    private static String nestedObjects(int depth) {
        StringBuilder json = new StringBuilder();
        for (int index = 0; index < depth; index++) json.append("{\"x\":");
        json.append('0');
        for (int index = 0; index < depth; index++) json.append('}');
        return json.toString();
    }

    private interface ThrowingRunnable {
        void run();
    }
}
