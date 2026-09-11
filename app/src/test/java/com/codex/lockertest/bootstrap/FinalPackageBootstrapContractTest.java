package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Behavioral compatibility checks for the final server response package. */
public final class FinalPackageBootstrapContractTest {
    @Test
    public void numericStatusFieldsAreAcceptedAndNormalizedToStrings() {
        ApiResult<BaseSettingSnapshot> base = new BaseSettingResponseParser().parse(
                baseSetting("\"取柜须知\"", "\"还柜须知\"", "2"));
        assertTrue(base.isSuccess());
        assertEquals("2", base.value().lockerCheckStatus());

        ApiResult<BasicDataSnapshot> basic = new BasicDataResponseParser().parse(
                ok("{\"num\":32,\"surplus_num\":12,"
                        + "\"dynamic_verification_code\":1}"));
        assertTrue(basic.isSuccess());
        assertEquals("1", basic.value().dynamicVerificationCode());
    }

    @Test
    public void multilineNoticesPreserveNewlinesCarriageReturnsAndTabs() {
        ApiResult<BaseSettingSnapshot> result = new BaseSettingResponseParser().parse(
                baseSetting("\"第一行\\n第二行\"", "\"退还\\r说明\\t结束\"", "\"0\""));

        assertTrue(result.isSuccess());
        assertEquals("第一行\n第二行", result.value().useNotice());
        assertEquals("退还\r说明\t结束", result.value().returnNotice());
    }

    @Test
    public void cozyTipsPreserveSafeMultilineFormatting() {
        ApiResult<BaseSettingSnapshot> result = new BaseSettingResponseParser().parse(
                baseSetting("\"U\"", "\"R\"", "0")
                        .replace("\"cozy_tips\":\"TEST_TIP\"",
                                "\"cozy_tips\":\"第一行\\n第二行\\r\\n请\\t保管好凭证\""));

        assertTrue(result.isSuccess());
        assertEquals("第一行\n第二行\r\n请\t保管好凭证", result.value().cozyTips());
    }

    @Test
    public void cozyTipsRemainBoundedAndRejectNonFormattingControls() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String template = baseSetting("\"U\"", "\"R\"", "0");
        for (String accepted : Arrays.asList("\"\"", "\"" + repeat('t', 32768) + "\"")) {
            assertTrue(parser.parse(template.replace("\"TEST_TIP\"", accepted))
                    .isSuccess());
        }
        for (String invalid : Arrays.asList(
                "null", "1", "[]", "\"bad\\u0000tip\"", "\"bad\\btip\"",
                "\"bad\\ftip\"", "\"bad\\u001btip\"", "\"bad\\u007ftip\"",
                "\"bad\\u0085tip\"", "\"" + repeat('t', 32769) + "\"")) {
            assertContract(parser.parse(template.replace("\"TEST_TIP\"", invalid)));
        }
    }

    @Test
    public void areaIdsAcceptCanonicalPositiveNumericStringsAndNumbers() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String template = baseSetting("\"U\"", "\"R\"", "0");
        String[] ids = {"1", "17", "2147483647", "\"1\"", "\"17\"", "\"2147483647\""};
        int[] expected = {1, 17, 2147483647, 1, 17, 2147483647};
        for (int index = 0; index < ids.length; index++) {
            ApiResult<BaseSettingSnapshot> result = parser.parse(template.replace(
                    "\"area_id\":1", "\"area_id\":" + ids[index]));
            assertTrue("area_id=" + ids[index], result.isSuccess());
            assertEquals(expected[index], result.value().areas().get(0).areaId());
        }
    }

    @Test
    public void areaIdsRejectZeroNoncanonicalStringsOverflowAndWrongTypes() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String template = baseSetting("\"U\"", "\"R\"", "0");
        for (String invalid : Arrays.asList(
                "0", "-1", "-0", "1.0", "1e0", "2147483648",
                "\"\"", "\"0\"", "\"-1\"", "\"-0\"", "\"+1\"", "\"01\"",
                "\"1.0\"", "\"1e0\"", "\" 1\"", "\"1 \"", "\"1\\n\"",
                "\"1\\u0000\"", "\"１\"", "\"2147483648\"", "\"9999999999\"",
                "\"9223372036854775808\"", "null", "true", "[]", "{}")) {
            assertContract(parser.parse(template.replace(
                    "\"area_id\":1", "\"area_id\":" + invalid)));
        }
    }

    @Test
    public void finalBaseSettingFieldsArePreservedWithoutBreakingOldPayloads() {
        ApiResult<BaseSettingSnapshot> result = new BaseSettingResponseParser().parse(
                finalBaseSetting("\"test-license-value\"",
                        "{\"柜门已开\":\"https://example.invalid/open.mp3\"}"));

        assertTrue(result.isSuccess());
        assertEquals("2", result.value().lockerCheckStatus());
        assertEquals("第一行\n第二行", result.value().useNotice());
        assertEquals("test-license-value", result.value().activationCode());
        assertEquals("https://example.invalid/open.mp3",
                result.value().voiceFiles().get("柜门已开"));
        try {
            result.value().voiceFiles().put("新提示", "https://example.invalid/new.mp3");
            fail("voice map must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        ApiResult<BaseSettingSnapshot> old = new BaseSettingResponseParser().parse(
                baseSetting("\"U\"", "\"R\"", "\"0\""));
        assertTrue(old.isSuccess());
        assertEquals("", old.value().activationCode());
        assertTrue(old.value().voiceFiles().isEmpty());
    }

    @Test
    public void statusFieldsRejectNoncanonicalNumbersWhitespaceAndUnknownEnums() {
        BaseSettingResponseParser baseParser = new BaseSettingResponseParser();
        for (String invalid : Arrays.asList(
                "0.0", "1e0", "-0", "-1", "3", "\" 1\"", "\"1 \"",
                "\"-0\"", "\"3\"", "null")) {
            assertContract(baseParser.parse(baseSetting("\"U\"", "\"R\"", invalid)));
        }

        BasicDataResponseParser basicParser = new BasicDataResponseParser();
        for (String invalid : Arrays.asList(
                "0.0", "1e0", "-0", "-1", "2", "\" 1\"", "\"1 \"",
                "\"-0\"", "\"2\"", "null")) {
            assertContract(basicParser.parse(basicData(invalid)));
        }
        for (String accepted : Arrays.asList("0", "1", "\"0\"", "\"1\"")) {
            assertTrue(basicParser.parse(basicData(accepted)).isSuccess());
        }
    }

    @Test
    public void activationCodeIsOptionalBoundedAndSingleLine() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        assertTrue(parser.parse(finalBaseSetting("\"\"", "{}")).isSuccess());
        assertTrue(parser.parse(finalBaseSetting("\"" + repeat('a', 256) + "\"", "{}"))
                .isSuccess());
        for (String invalid : Arrays.asList(
                "null", "1", "[]", "\"bad\\ncode\"",
                "\"" + repeat('a', 257) + "\"")) {
            assertContract(parser.parse(finalBaseSetting(invalid, "{}")));
        }
    }

    @Test
    public void voiceObjectIsBoundedValidatedAndEmptyArrayCompatible() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        assertTrue(parser.parse(finalBaseSetting("\"\"", voiceObject(32))).isSuccess());
        assertTrue(parser.parse(finalBaseSetting("\"\"", "[]")).isSuccess());

        for (String invalid : Arrays.asList(
                voiceObject(33),
                "[{\"label\":\"柜门已开\"}]",
                "null", "\"voice\"", "1",
                "{\"\":\"https://example.invalid/open.mp3\"}",
                "{\"" + repeat('l', 129) + "\":\"https://example.invalid/open.mp3\"}",
                "{\"bad\\nlabel\":\"https://example.invalid/open.mp3\"}",
                "{\"柜门已开\":\"\"}",
                "{\"柜门已开\":\"" + repeat('u', 2049) + "\"}",
                "{\"柜门已开\":\"bad\\turl\"}",
                "{\"柜门已开\":null}")) {
            assertContract(parser.parse(finalBaseSetting("\"\"", invalid)));
        }
    }

    @Test
    public void noticesRejectOtherControlsAndOtherFieldsStaySingleLine() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        assertContract(parser.parse(baseSetting("\"bad\\bnotice\"", "\"R\"", "\"0\"")));
        assertContract(parser.parse(baseSetting("\"U\"", "\"bad\\u0000notice\"", "\"0\"")));
        assertContract(parser.parse(baseSetting("\"U\"", "\"R\"", "\"0\"")
                .replace("\"venue_name\":\"TEST_VENUE\"",
                        "\"venue_name\":\"bad\\nvenue\"")));
    }

    @Test
    public void deviceNumberAcceptsStringsOrArbitraryPrecisionCanonicalUnsignedIntegers() {
        CheckDeviceResponseParser parser = new CheckDeviceResponseParser();
        assertEquals("000123", successfulDeviceNo(parser, "\"000123\""));
        assertEquals("0", successfulDeviceNo(parser, "0"));
        String large = repeat('9', 256);
        assertEquals(large, successfulDeviceNo(parser, large));

        for (String invalid : Arrays.asList(
                "-0", "-1", "1.0", "1e0", "null", "true",
                repeat('9', 257), "\"" + repeat('9', 257) + "\"")) {
            assertContract(parser.parse(checkDevice(invalid)));
        }
        assertContract(parser.parse(ok("{\"merchant_code\":1,\"device_no\":0}")));
    }

    @Test
    public void optionalBaseKeysDoNotRelaxTheUnknownKeyRejection() {
        String withUnknown = finalBaseSetting("\"\"", "{}")
                .replace("\"voice\":{}", "\"voice\":{},\"unknown\":true");
        assertContract(new BaseSettingResponseParser().parse(withUnknown));
    }

    private static String baseSetting(
            String useNotice, String returnNotice, String lockerStatus) {
        return ok("{\"venue_name\":\"TEST_VENUE\",\"version\":\"TEST_VERSION\","
                + "\"recognition_type\":[\"1\",\"2\"],"
                + "\"use_notice\":" + useNotice + ","
                + "\"return_notice\":" + returnNotice + ","
                + "\"logo\":\"https://example.invalid/logo.png\","
                + "\"company\":\"TEST_COMPANY\",\"cozy_tips\":\"TEST_TIP\","
                + "\"advertisement_type\":0,\"advertisement_list\":[],"
                + "\"background_img\":\"https://example.invalid/background.png\","
                + "\"palm_print_img\":\"https://example.invalid/palm.png\","
                + "\"area_list\":[{\"area_id\":1,\"name\":\"TEST_AREA\"}],"
                + "\"locker_check_status\":" + lockerStatus + "}");
    }

    private static String finalBaseSetting(String activationCode, String voice) {
        String old = baseSetting("\"第一行\\n第二行\"", "\"还柜说明\"", "2");
        return old.substring(0, old.length() - 2)
                + ",\"activation_code\":" + activationCode
                + ",\"voice\":" + voice + "}}";
    }

    private static String basicData(String dynamic) {
        return ok("{\"num\":32,\"surplus_num\":12,"
                + "\"dynamic_verification_code\":" + dynamic + "}");
    }

    private static String checkDevice(String deviceNo) {
        return ok("{\"merchant_code\":\"TEST_MERCHANT\",\"device_no\":"
                + deviceNo + "}");
    }

    private static String successfulDeviceNo(
            CheckDeviceResponseParser parser, String deviceNo) {
        ApiResult<DeviceRegistration> result = parser.parse(checkDevice(deviceNo));
        assertTrue(result.isSuccess());
        return result.value().deviceNo();
    }

    private static String voiceObject(int count) {
        StringBuilder json = new StringBuilder("{");
        for (int index = 0; index < count; index++) {
            if (index != 0) json.append(',');
            json.append("\"提示").append(index).append("\":")
                    .append("\"https://example.invalid/").append(index).append(".mp3\"");
        }
        return json.append('}').toString();
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static void assertContract(ApiResult<?> result) {
        assertFalse(result.isSuccess());
        assertEquals(com.codex.lockertest.server.ServerFailure.Kind.CONTRACT,
                result.failure().kind());
    }

    private static String ok(String data) {
        return "{\"code\":200,\"message\":\"ok\",\"data\":" + data + "}";
    }
}
