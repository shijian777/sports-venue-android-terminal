package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.ServerFailure;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BootstrapResponseParserTest {
    private static final String CHECK_DATA =
            "{\"merchant_code\":\"MERCHANT\",\"device_no\":\"DEVICE\"}";

    @Test
    public void officialSanitizedFixturesParseWithSampleFaithfulTypes() throws Exception {
        ApiResult<DeviceRegistration> check = new CheckDeviceResponseParser().parse(
                fixture("check-device-success-official-sanitized.json"));
        assertTrue(check.isSuccess());
        assertEquals("TEST_MERCHANT_CODE", check.value().merchantCode());
        assertEquals("TEST_DEVICE_NO", check.value().deviceNo());

        ApiResult<BaseSettingSnapshot> base = new BaseSettingResponseParser().parse(
                fixture("base-setting-success-official-sanitized.json"));
        assertTrue(base.isSuccess());
        assertEquals("TEST_VENUE_NAME", base.value().venueName());
        assertEquals("TEST_VERSION", base.value().version());
        assertEquals(Arrays.asList("1", "2", "3", "4", "5"),
                base.value().recognitionTypes());
        assertEquals(0, base.value().advertisementType());
        assertEquals("TEST_AD_IMAGE_URL", base.value().advertisements().get(0).image());
        assertEquals("", base.value().advertisements().get(0).video());
        assertEquals(2, base.value().areas().get(0).areaId());
        assertEquals("TEST_AREA_NAME", base.value().areas().get(0).areaName());
        assertEquals("0", base.value().lockerCheckStatus());

        ApiResult<BasicDataSnapshot> basic = new BasicDataResponseParser().parse(
                fixture("basic-data-success-official-sanitized.json"));
        assertTrue(basic.isSuccess());
        assertEquals(0, basic.value().totalLockers());
        assertEquals(0, basic.value().availableLockers());
        assertEquals("1", basic.value().dynamicVerificationCode());
    }

    @Test
    public void invalidJsonIsDistinctFromAValidButWrongContract() {
        for (String invalid : Arrays.asList(
                "{", "{\"code\":0200}",
                "{\"code\":200,\"code\":200,\"message\":\"ok\",\"data\":"
                        + CHECK_DATA + "}")) {
            assertFailure(new CheckDeviceResponseParser().parse(invalid),
                    ServerFailure.Kind.INVALID_JSON);
        }
        assertFailure(new CheckDeviceResponseParser().parse("[]"),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void onlyCanonicalRawIntegerCode200IsSuccess() {
        for (String code : Arrays.asList("\"200\"", "200.0", "2e2", "200e0", "1.0e2",
                "null", "2147483648", "-0")) {
            assertFailure(new CheckDeviceResponseParser().parse(
                    envelope(code, "\"ok\"", CHECK_DATA)), ServerFailure.Kind.CONTRACT);
        }
        assertFailure(new CheckDeviceResponseParser().parse(
                "{\"message\":\"ok\",\"data\":" + CHECK_DATA + "}"),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void everyCanonicalNon200IsRejectedWithoutAssumingMessageOrData() throws Exception {
        assertFailure(new CheckDeviceResponseParser().parse(fixture("non-200-synthetic.json")),
                ServerFailure.Kind.REMOTE_REJECTED);
        assertFailure(new CheckDeviceResponseParser().parse("{\"code\":2}"),
                ServerFailure.Kind.REMOTE_REJECTED);
        assertFailure(new BaseSettingResponseParser().parse(
                "{\"code\":400,\"unexpected\":null,\"data\":[1]}"),
                ServerFailure.Kind.REMOTE_REJECTED);
        assertFailure(new BasicDataResponseParser().parse("{\"code\":-1}"),
                ServerFailure.Kind.REMOTE_REJECTED);
    }

    @Test
    public void successEnvelopeRequiresExactFieldsBoundedMessageAndObjectData() {
        CheckDeviceResponseParser parser = new CheckDeviceResponseParser();
        assertFailure(parser.parse("{\"code\":200,\"data\":" + CHECK_DATA + "}"),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(envelope("200", "null", CHECK_DATA)),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(envelope("200", "\"" + repeat('m', 513) + "\"", CHECK_DATA)),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(envelope("200", "\"bad\\nmessage\"", CHECK_DATA)),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(envelope("200", "\"ok\"", "[]")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse("{\"code\":200,\"message\":\"ok\",\"data\":"
                + CHECK_DATA + ",\"extra\":true}"), ServerFailure.Kind.CONTRACT);
        assertTrue(parser.parse(envelope("200", "\"" + repeat('m', 512) + "\"",
                CHECK_DATA)).isSuccess());
    }

    @Test
    public void checkDeviceDataUsesExactHeaderFields() {
        CheckDeviceResponseParser parser = new CheckDeviceResponseParser();
        assertFailure(parser.parse(ok("{\"merchant_code\":\"M\"}")),
                ServerFailure.Kind.CONTRACT);
        ApiResult<DeviceRegistration> numericDevice = parser.parse(ok(
                "{\"merchant_code\":\"M\",\"device_no\":1}"));
        assertTrue(numericDevice.isSuccess());
        assertEquals("1", numericDevice.value().deviceNo());
        assertFailure(parser.parse(ok(
                "{\"merchant_code\":\"M\\n\",\"device_no\":\"D\"}")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(ok(
                "{\"merchant_code\":\"M\",\"device_no\":\"D\",\"x\":0}")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void baseSettingRecognitionAndStatusKeepExactEnums() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        assertTrue(parser.parse(base("[\"1\",\"5\"]", "0", "[]", "[]", "2"))
                .isSuccess());
        for (String recognition : Arrays.asList(
                "[1]", "[\"6\"]", "[\"1\",\"1\"]", "null", "{}")) {
            assertFailure(parser.parse(base(recognition, "0", "[]", "[]", "2")),
                    ServerFailure.Kind.CONTRACT);
        }
        ApiResult<BaseSettingSnapshot> numericStatus = parser.parse(
                base("[]", "0", "[]", "[]", "0")
                        .replace("\"locker_check_status\":\"0\"",
                                "\"locker_check_status\":0"));
        assertTrue(numericStatus.isSuccess());
        assertEquals("0", numericStatus.value().lockerCheckStatus());
        for (String status : Arrays.asList("\"3\"", "null")) {
            assertFailure(parser.parse(base("[]", "0", "[]", "[]", status)),
                    ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void baseSettingAcceptsRenamedVerificationStatusAndRejectsConflictingAlias() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String legacy = base("[]", "0", "[]", "[]", "1");
        String renamed = legacy.replace(
                "\"locker_check_status\":\"1\"",
                "\"verification_result_status\":\"1\"");

        ApiResult<BaseSettingSnapshot> stringStatus = parser.parse(renamed);
        assertTrue(stringStatus.isSuccess());
        assertEquals("1", stringStatus.value().lockerCheckStatus());

        ApiResult<BaseSettingSnapshot> numericStatus = parser.parse(renamed.replace(
                "\"verification_result_status\":\"1\"",
                "\"verification_result_status\":2"));
        assertTrue(numericStatus.isSuccess());
        assertEquals("2", numericStatus.value().lockerCheckStatus());

        assertTrue(parser.parse(renamed.replace(
                "\"verification_result_status\":\"1\"",
                "\"verification_result_status\":\"1\",\"locker_check_status\":1"))
                .isSuccess());
        assertFailure(parser.parse(renamed.replace(
                "\"verification_result_status\":\"1\"",
                "\"verification_result_status\":\"1\",\"locker_check_status\":2")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(renamed.replace(
                ",\"verification_result_status\":\"1\"", "")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void advertisementTypeAndItemsHonorCanonicalLimitsWithoutGuessingUrls() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String emptyItem = "{\"image\":\"\",\"video\":\"\"}";
        assertTrue(parser.parse(base("[]", "0", arrayOf(emptyItem, 32), "[]", "0"))
                .isSuccess());
        assertFailure(parser.parse(base("[]", "0", arrayOf(emptyItem, 33), "[]", "0")),
                ServerFailure.Kind.CONTRACT);
        assertTrue(parser.parse(base("[]", "1", arrayOf(emptyItem, 1), "[]", "0"))
                .isSuccess());
        assertFailure(parser.parse(base("[]", "1", arrayOf(emptyItem, 2), "[]", "0")),
                ServerFailure.Kind.CONTRACT);
        for (String type : Arrays.asList("2", "0.0", "\"0\"", "null")) {
            assertFailure(parser.parse(base("[]", type, "[]", "[]", "0")),
                    ServerFailure.Kind.CONTRACT);
        }
        assertFailure(parser.parse(base("[]", "0",
                "[{\"image\":1,\"video\":\"\"}]", "[]", "0")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(base("[]", "0",
                "[{\"image\":\"bad\\n\",\"video\":\"\"}]", "[]", "0")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(base("[]", "0",
                "[{\"image\":\"" + repeat('x', 2049) + "\",\"video\":\"\"}]",
                "[]", "0")), ServerFailure.Kind.CONTRACT);
        assertTrue(parser.parse(base("[]", "0",
                "[{\"image\":\"" + repeat('x', 2048) + "\",\"video\":\"\"}]",
                "[]", "0")).isSuccess());
        assertFailure(parser.parse(base("[]", "0",
                "[{\"image\":\"\",\"video\":\"\",\"url\":\"x\"}]",
                "[]", "0")), ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void areaListIsFlatWithPositiveIntIdAndBoundedNonemptyName() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        assertTrue(parser.parse(base("[]", "0", "[]",
                "[{\"area_id\":2147483647,\"name\":\"" + repeat('a', 256)
                        + "\"}]", "0")).isSuccess());
        for (String areas : Arrays.asList(
                "[{\"area_id\":0,\"name\":\"A\"}]",
                "[{\"area_id\":-1,\"name\":\"A\"}]",
                "[{\"area_id\":2147483648,\"name\":\"A\"}]",
                "[{\"area_id\":1.0,\"name\":\"A\"}]",
                "[{\"area_id\":\"01\",\"name\":\"A\"}]",
                "[{\"area_id\":1,\"name\":\"\"}]",
                "[{\"area_id\":1,\"name\":\"A\\n\"}]",
                "[{\"area_id\":1,\"name\":\"" + repeat('a', 257) + "\"}]",
                "[[{\"area_id\":1,\"name\":\"A\"}]]",
                "[{\"area_id\":1,\"name\":\"A\",\"extra\":0}]")) {
            assertFailure(parser.parse(base("[]", "0", "[]", areas, "0")),
                    ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void baseSettingRequiresItsExactDocumentedFieldAllowlist() {
        BaseSettingResponseParser parser = new BaseSettingResponseParser();
        String valid = base("[]", "0", "[]", "[]", "0");
        assertFailure(parser.parse(valid.replace("\"venue_name\":\"V\",", "")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(valid.replace("\"venue_name\":\"V\",",
                "\"venue_name\":null,")), ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(valid.replace("\"venue_name\":\"V\",",
                "\"venue_name\":\"V\",\"unknown\":0,")), ServerFailure.Kind.CONTRACT);
        assertFailure(parser.parse(valid.replace("\"venue_name\":\"V\",",
                "\"venue_name\":\"bad\\n\",")), ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void basicDataUsesCanonicalBoundedCountsAndExactFlag() {
        BasicDataResponseParser parser = new BasicDataResponseParser();
        assertTrue(parser.parse(basic("1000000", "1000000", "\"0\""))
                .isSuccess());
        ApiResult<BasicDataSnapshot> numericFlag = parser.parse(basic("1", "0", "0"));
        assertTrue(numericFlag.isSuccess());
        assertEquals("0", numericFlag.value().dynamicVerificationCode());
        for (String json : Arrays.asList(
                basic("-1", "0", "\"0\""),
                basic("1000001", "0", "\"0\""),
                basic("1.0", "0", "\"0\""),
                basic("1", "2", "\"0\""),
                basic("1", "0", "\"2\""),
                basic("1", "0", "null"),
                ok("{\"num\":1,\"surplus_num\":0}"),
                ok("{\"num\":1,\"surplus_num\":0,"
                        + "\"dynamic_verification_code\":\"0\",\"extra\":0}"))) {
            assertFailure(parser.parse(json), ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void snapshotsAreImmutableDefensiveCopiesAndDoNotRetainRawPayloads() {
        List<String> recognition = new ArrayList<>(Arrays.asList("1"));
        List<BaseSettingSnapshot.Advertisement> ads = new ArrayList<>(Arrays.asList(
                new BaseSettingSnapshot.Advertisement("i", "v")));
        List<BaseSettingSnapshot.Area> areas = new ArrayList<>(Arrays.asList(
                new BaseSettingSnapshot.Area(1, "A")));
        BaseSettingSnapshot snapshot = new BaseSettingSnapshot(
                "V", "1", recognition, "U", "R", "L", "C", "T", 0,
                ads, "B", "P", areas, "0");
        recognition.clear();
        ads.clear();
        areas.clear();

        assertEquals(Arrays.asList("1"), snapshot.recognitionTypes());
        assertEquals(1, snapshot.advertisements().size());
        assertEquals(1, snapshot.areas().size());
        assertUnmodifiable(snapshot.recognitionTypes());
        assertUnmodifiable(snapshot.advertisements());
        assertUnmodifiable(snapshot.areas());
        assertNoSensitivePayloadField(DeviceRegistration.class);
        assertNoSensitivePayloadField(BaseSettingSnapshot.class);
        assertNoSensitivePayloadField(BasicDataSnapshot.class);
    }

    private static String envelope(String code, String message, String data) {
        return "{\"code\":" + code + ",\"message\":" + message + ",\"data\":"
                + data + "}";
    }

    private static String ok(String data) {
        return envelope("200", "\"ok\"", data);
    }

    private static String base(
            String recognition, String advertisementType, String advertisements,
            String areas, String lockerStatus) {
        return ok("{\"venue_name\":\"V\",\"version\":\"1\","
                + "\"recognition_type\":" + recognition + ","
                + "\"use_notice\":\"U\",\"return_notice\":\"R\","
                + "\"logo\":\"L\",\"company\":\"C\",\"cozy_tips\":\"T\","
                + "\"advertisement_type\":" + advertisementType + ","
                + "\"advertisement_list\":" + advertisements + ","
                + "\"background_img\":\"B\",\"palm_print_img\":\"P\","
                + "\"area_list\":" + areas + ","
                + "\"locker_check_status\":" + jsonStringOrToken(lockerStatus) + "}");
    }

    private static String basic(String total, String available, String dynamic) {
        return ok("{\"num\":" + total + ",\"surplus_num\":" + available
                + ",\"dynamic_verification_code\":" + dynamic + "}");
    }

    private static String jsonStringOrToken(String value) {
        if (value.startsWith("\"") || "null".equals(value)
                || value.startsWith("[") || value.startsWith("{")) return value;
        return "\"" + value + "\"";
    }

    private static String arrayOf(String item, int count) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index != 0) result.append(',');
            result.append(item);
        }
        return result.append(']').toString();
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind kind) {
        assertFalse(result.isSuccess());
        assertEquals(kind, result.failure().kind());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertUnmodifiable(List<?> values) {
        try {
            ((List) values).add(new Object());
            fail("list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void assertNoSensitivePayloadField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.US);
            assertFalse(name, name.contains("message"));
            assertFalse(name, name.contains("json"));
            assertFalse(name, name.contains("serial"));
            assertFalse(name, name.contains("header"));
            assertFalse(name, name.contains("key"));
        }
    }

    private static String fixture(String name) throws Exception {
        Path root = Paths.get(System.getProperty("codex.projectRoot", "."))
                .toAbsolutePath().normalize();
        return new String(Files.readAllBytes(
                root.resolve("app/src/test/fixtures/server/" + name)),
                StandardCharsets.UTF_8);
    }
}
