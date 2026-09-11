package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.ServerFailure;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BusinessModelsAndParsersTest {
    @Test
    public void credentialFactoriesPreserveLeadingZeroesAndVariableQrLength() {
        UserInfoRequest phone = UserInfoRequest.phone("0013800000000", "000042");
        assertEquals(1, phone.type());
        assertEquals("0013800000000", phone.keyword());
        assertEquals("000042", phone.userCode());

        UserInfoRequest qr = UserInfoRequest.qr("Q:0/abc-0123456789");
        assertEquals(2, qr.type());
        assertEquals("Q:0/abc-0123456789", qr.keyword());
        assertFalse(qr.hasUserCode());

        assertEquals(4, UserInfoRequest.card("00000019").type());
        assertEquals(5, UserInfoRequest.faceImage("image/path/0001").type());
    }

    @Test
    public void palmRequestObtainsOpaqueKeywordOnlyFromProvider() {
        UserInfoRequest palm = UserInfoRequest.palm(new PalmKeywordProvider() {
            @Override
            public String keyword() {
                return "opaque-person-id";
            }
        });
        assertEquals(3, palm.type());
        assertEquals("opaque-person-id", palm.keyword());
    }

    @Test
    public void credentialsRejectBlankControlCharactersAndExcessiveInput() {
        reject(new Attempt() { @Override public void run() { UserInfoRequest.phone("", "1"); } });
        reject(new Attempt() { @Override public void run() { UserInfoRequest.qr("   "); } });
        reject(new Attempt() { @Override public void run() { UserInfoRequest.phone("1", "\n"); } });
        reject(new Attempt() { @Override public void run() { UserInfoRequest.qr(repeat('q', 4097)); } });
        reject(new Attempt() { @Override public void run() { UserInfoRequest.palm(null); } });
    }

    @Test
    public void userInfoParserAcceptsDocumentTableAndExampleAliasesButRejectsBoth() {
        ApiResult<AuthenticatedUser> table = BusinessResponseParsers.userInfo(
                authFixture("\"user_type\":0", "0"));
        assertTrue(table.isSuccess());
        assertTrue(table.value().isCustomerReady());
        assertEquals("<redacted-session-token>", table.value().token().toString());

        ApiResult<AuthenticatedUser> example = BusinessResponseParsers.userInfo(
                authFixture("\"usr_type\":1", "1")
                        .replace("locker_check_status", "dynamic_verification_code"));
        assertTrue(example.isSuccess());
        assertFalse(example.value().isCustomerReady());
        assertTrue(example.value().dynamicCodeRequired());

        assertFailure(BusinessResponseParsers.userInfo(
                authFixture("\"user_type\":0,\"usr_type\":0", "0")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void userInfoParserRejectsMissingUnknownAndMalformedFields() {
        assertFailure(BusinessResponseParsers.userInfo(
                "{\"code\":200,\"message\":\"ok\",\"data\":"
                        + "{\"token\":\"t\",\"user_type\":0,"
                        + "\"locker_check_status\":0}}"), ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.userInfo(
                authFixture("\"user_type\":0,\"extra\":1", "0")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.userInfo("not-json"),
                ServerFailure.Kind.INVALID_JSON);
        assertFailure(BusinessResponseParsers.userInfo(
                "{\"code\":400,\"message\":\"no\",\"data\":[]}"),
                ServerFailure.Kind.REMOTE_REJECTED);
    }

    @Test
    public void previewParserKeepsNumericLayerKeysAndOpaqueCommandMetadata() {
        String fixture = "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"page\":[\"1\",2],"
                + "\"all_open_command\":[{\"board_hex\":\"01\","
                + "\"command\":\"8A 01 00 11 9A\"}],"
                + "\"0\":[{" + cabinetFixture(7, 9, "007", 0, 2,
                        "01", "00", "8A 01 00 11 9A", 0) + "}],"
                + "\"3\":[{" + cabinetFixture(8, 10, "008", 1, 2,
                        "02", "0A", "8A 02 0A 11 91", 3) + "}]}}";

        ApiResult<ControlPanelPreview> result =
                BusinessResponseParsers.controlPanelPreview(fixture);
        assertTrue(result.isSuccess());
        assertEquals(Arrays.asList(1, 2), result.value().pages());
        assertEquals("01", result.value().allOpenCommands().get(0).boardHex());
        assertEquals("007", result.value().layers().get(0).get(0).cabinetLabel());
        assertEquals("8A 02 0A 11 91",
                result.value().layers().get(3).get(0).openCommand());
    }

    @Test
    public void previewParserAcceptsVerifiedCanonicalNumericStringsWithoutChangingCommands() {
        ApiResult<ControlPanelPreview> result = BusinessResponseParsers.controlPanelPreview(
                previewFixture("\"0\":[{" + stringNumericCabinetFixture() + "}]"));

        assertTrue(result.isSuccess());
        ControlPanelPreview.Cabinet cabinet = result.value().layers().get(0).get(0);
        assertEquals(7L, cabinet.fcId());
        assertEquals(9L, cabinet.channelId());
        assertEquals(0, cabinet.status());
        assertEquals(2L, cabinet.areaId());
        assertEquals("007", cabinet.cabinetLabel());
        assertEquals("01", cabinet.boardHex());
        assertEquals("00", cabinet.channelNo());
        assertEquals("8A 01 00 11 9A", cabinet.openCommand());
        assertEquals(0, cabinet.checkStatus());
        assertEquals(Arrays.asList(1), result.value().pages());
    }

    @Test
    public void previewParserAcceptsMixedNumbersAndStringsAtExistingRangeBoundaries() {
        String fixture = stringNumericCabinetFixture()
                .replace("\"fc_id\":\"7\"", "\"fc_id\":\"9223372036854775807\"")
                .replace("\"channel_id\":\"9\"", "\"channel_id\":1")
                .replace("\"status\":\"0\"", "\"status\":\"4\"")
                .replace("\"area_id\":\"2\"", "\"area_id\":9223372036854775807");

        ApiResult<ControlPanelPreview> result = BusinessResponseParsers.controlPanelPreview(
                previewFixture("\"0\":[{" + fixture + "}]"));

        assertTrue(result.isSuccess());
        ControlPanelPreview.Cabinet cabinet = result.value().layers().get(0).get(0);
        assertEquals(Long.MAX_VALUE, cabinet.fcId());
        assertEquals(1L, cabinet.channelId());
        assertEquals(4, cabinet.status());
        assertEquals(Long.MAX_VALUE, cabinet.areaId());
    }

    @Test
    public void previewParserRejectsMalformedNumericStringsAndWrongTypesForEveryVerifiedField() {
        String[] fields = {"fc_id", "channel_id", "status", "area_id"};
        String[] validValues = {"7", "9", "0", "2"};
        String[] invalidValues = {"\"\"", "\" 1\"", "\"1 \"", "\"01\"", "\"00\"",
                "\"+1\"", "\"-1\"", "\"-0\"", "\"1.0\"", "\"1e0\"", "\"1E0\"",
                "\"0x1\"", "\"１\"", "\"1\\n\"", "\"9223372036854775808\"",
                "\"9999999999999999999999999\"", "true", "null", "[]", "{}", "1.0", "1e0"};
        for (int index = 0; index < fields.length; index++) {
            for (String invalidValue : invalidValues) {
                String fixture = stringNumericCabinetFixture().replace(
                        "\"" + fields[index] + "\":\"" + validValues[index] + "\"",
                        "\"" + fields[index] + "\":" + invalidValue);
                ApiResult<ControlPanelPreview> result = BusinessResponseParsers.controlPanelPreview(
                        previewFixture("\"0\":[{" + fixture + "}]"));
                assertFalse(fields[index] + " accepted " + invalidValue, result.isSuccess());
                assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
            }
        }
    }

    @Test
    public void previewParserRetainsPositiveIdAndStatusRangeRequirements() {
        String[] fields = {"fc_id", "channel_id", "status", "area_id"};
        String[] validValues = {"7", "9", "0", "2"};
        String[] invalidValues = {"0", "0", "5", "0"};
        for (int index = 0; index < fields.length; index++) {
            String fixture = stringNumericCabinetFixture().replace(
                    "\"" + fields[index] + "\":\"" + validValues[index] + "\"",
                    "\"" + fields[index] + "\":\"" + invalidValues[index] + "\"");
            assertFailure(BusinessResponseParsers.controlPanelPreview(
                    previewFixture("\"0\":[{" + fixture + "}]")), ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void previewParserAcceptsOnlyUniqueCanonicalNumberOrStringPages() {
        String valid = previewFixture("\"0\":[{" + stringNumericCabinetFixture() + "}]");
        assertFailure(BusinessResponseParsers.controlPanelPreview(
                valid.replace("\"page\":[1],", "")), ServerFailure.Kind.CONTRACT);
        ApiResult<ControlPanelPreview> boundaries = BusinessResponseParsers.controlPanelPreview(
                valid.replace("\"page\":[1]", "\"page\":[\"1\",100]"));
        assertTrue(boundaries.isSuccess());
        assertEquals(Arrays.asList(1, 100), boundaries.value().pages());

        String[] invalidPages = {"[]", "null", "1", "{}", "[0]", "[101]",
                "[1,1]", "[1,\"1\"]", "[\"01\"]", "[\"00\"]", "[\"+1\"]",
                "[\"-1\"]", "[\"1.0\"]", "[\"1e0\"]", "[1.0]", "[1e0]",
                "[true]", "[null]", "[[]]", "[{}]"};
        for (String invalidPagesValue : invalidPages) {
            assertFailure(BusinessResponseParsers.controlPanelPreview(
                    valid.replace("\"page\":[1]", "\"page\":" + invalidPagesValue)),
                    ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void previewParserStillRequiresNumericCheckStatus() {
        String valid = previewFixture("\"0\":[{" + stringNumericCabinetFixture() + "}]");
        assertFailure(BusinessResponseParsers.controlPanelPreview(
                valid.replace("\"check_status\":0", "\"check_status\":\"0\"")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void cabinetNumericCompatibilityDoesNotCoerceIdentityOrOtherEndpointFields() {
        String[] invalidRoleFields = {"\"user_type\":\"0\"", "\"user_type\":{}",
                "\"user_type\":{\"role\":0}", "\"usr_type\":\"1\""};
        for (String field : invalidRoleFields) {
            assertFailure(BusinessResponseParsers.userInfo(authFixture(field, "0")),
                    ServerFailure.Kind.CONTRACT);
        }
        assertFailure(BusinessResponseParsers.userInfo(
                authFixture("\"user_type\":0", "\"00\"")), ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.userInfo(
                authFixture("\"user_type\":0", "0").replace("\"uid\":9", "\"uid\":\"9\"")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.userBoard(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"fc_id\":\"17\"}}"),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void previewParserRejectsNonnumericDuplicateAndPartialLayerData() {
        String cabinet = cabinetFixture(1, 1, "001", 0, 1,
                "01", "00", "8A 01 00 11 9A", 0);
        assertFailure(BusinessResponseParsers.controlPanelPreview(
                previewFixture("\"layer\":[{" + cabinet + "}]")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.controlPanelPreview(
                previewFixture("\"1\":[],\"01\":[]")),
                ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.controlPanelPreview(
                previewFixture("\"1\":[{\"fc_id\":1}]")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void usedCabinetWithoutOptionalNoticeStillListsVerifiedOwnedCabinet() {
        String fixture = "{\"code\":200,\"message\":\"success\",\"data\":{"
                + "\"user_name\":\"测试会员\",\"mobile\":\"13100000000\",\"list\":[{"
                + "\"record_id\":\"9\",\"fc_id\":\"20\",\"name\":\"008\","
                + "\"start_use\":\"2026-09-09 10:00:00\",\"use_time\":30}]}}";
        ApiResult<UsedCabinetList> result = BusinessResponseParsers.useCabinetList(fixture);
        assertTrue(result.isSuccess());
        assertEquals(1, result.value().cabinets().size());
        assertEquals(20L, result.value().cabinets().get(0).fcId());
        assertEquals(9L, result.value().cabinets().get(0).recordId());
        assertEquals("", result.value().cabinets().get(0).useNotice());

        assertFailure(BusinessResponseParsers.useCabinetList(
                fixture.replace("\"fc_id\":\"20\",", "")), ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.useCabinetList(
                fixture.replace("\"record_id\":\"9\",", "")), ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.useCabinetList(
                fixture.replace("\"use_time\":30", "\"use_time\":30,\"extra\":1")),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void usedCabinetParserPreservesServerIdsAndDisplayFields() {
        String fixture = "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"user_name\":\"U\",\"mobile\":\"00123\",\"list\":[{"
                + "\"record_id\":5,\"fc_id\":12,\"name\":\"A012\","
                + "\"start_use\":\"2026-09-06 10:00:00\",\"use_time\":15,"
                + "\"use_notice\":\"notice\"}]}}";
        ApiResult<UsedCabinetList> result = BusinessResponseParsers.useCabinetList(fixture);
        assertTrue(result.isSuccess());
        assertEquals("00123", result.value().mobile());
        assertEquals(5L, result.value().cabinets().get(0).recordId());
        assertEquals(12L, result.value().cabinets().get(0).fcId());
    }

    @Test
    public void usedCabinetParserAcceptsCanonicalStringIdsAndDocumentedDurationText() {
        ApiResult<UsedCabinetList> result = BusinessResponseParsers.useCabinetList(
                usedCabinetFixture("\"9223372036854775807\"", "\"12\"", "\"100分钟\""));

        assertTrue(result.isSuccess());
        UsedCabinet cabinet = result.value().cabinets().get(0);
        assertEquals(Long.MAX_VALUE, cabinet.recordId());
        assertEquals(12L, cabinet.fcId());
        assertEquals(100, cabinet.useTimeMinutes());

        String[] acceptedDurations = {
                "0", "\"0\"", "\"0分钟\"", "\"2147483647\"", "\"2147483647分钟\""
        };
        int[] expectedMinutes = {0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (int index = 0; index < acceptedDurations.length; index++) {
            ApiResult<UsedCabinetList> accepted = BusinessResponseParsers.useCabinetList(
                    usedCabinetFixture("5", "12", acceptedDurations[index]));
            assertTrue("use_time=" + acceptedDurations[index], accepted.isSuccess());
            assertEquals(expectedMinutes[index],
                    accepted.value().cabinets().get(0).useTimeMinutes());
        }
    }

    @Test
    public void usedCabinetParserRejectsNoncanonicalIdsAndDurations() {
        String[] invalidIds = {"0", "-1", "1.0", "1e0", "\"0\"", "\"-1\"",
                "\"01\"", "\"1.0\"", "\"1e0\"", "\"1分钟\"",
                "\"9223372036854775808\"", "true", "null", "[]", "{}"};
        for (String invalidId : invalidIds) {
            assertFailure(BusinessResponseParsers.useCabinetList(
                    usedCabinetFixture(invalidId, "12", "15")),
                    ServerFailure.Kind.CONTRACT);
            assertFailure(BusinessResponseParsers.useCabinetList(
                    usedCabinetFixture("5", invalidId, "15")),
                    ServerFailure.Kind.CONTRACT);
        }

        String[] invalidDurations = {"-1", "1.0", "1e0", "\"\"", "\"-1\"",
                "\"+1\"", "\"01\"", "\"01分钟\"", "\"1.0分钟\"",
                "\"1e0分钟\"", "\" 1分钟\"", "\"1 分钟\"", "\"分钟\"",
                "\"1hours\"", "\"2147483648\"", "\"2147483648分钟\"",
                "true", "null", "[]", "{}"};
        for (String invalidDuration : invalidDurations) {
            assertFailure(BusinessResponseParsers.useCabinetList(
                    usedCabinetFixture("5", "12", invalidDuration)),
                    ServerFailure.Kind.CONTRACT);
        }
    }

    @Test
    public void usedCabinetParserRejectsDuplicateCabinetIdsAcrossDifferentRecords() {
        String fixture = "{\"code\":200,\"message\":\"ok\",\"data\":{" 
                + "\"user_name\":\"U\",\"mobile\":\"00123\",\"list\":["
                + "{\"record_id\":5,\"fc_id\":12,\"name\":\"A012\","
                + "\"start_use\":\"2026-09-06 10:00:00\",\"use_time\":15,"
                + "\"use_notice\":\"first\"},"
                + "{\"record_id\":6,\"fc_id\":12,\"name\":\"wrong-label\","
                + "\"start_use\":\"2026-09-06 10:05:00\",\"use_time\":10,"
                + "\"use_notice\":\"second\"}]}}";

        assertFailure(BusinessResponseParsers.useCabinetList(fixture),
                ServerFailure.Kind.CONTRACT);
    }

    @Test
    public void endpointSpecificEmptyResponsesAcceptOnlyTheirDocumentedShape() {
        assertTrue(BusinessResponseParsers.empty(
                "{\"code\":200,\"message\":\"ok\",\"data\":[]}").isSuccess());
        assertFailure(BusinessResponseParsers.empty(
                "{\"code\":200,\"message\":\"ok\",\"data\":{}}"),
                ServerFailure.Kind.CONTRACT);
        assertTrue(BusinessResponseParsers.emptyObject(
                "{\"code\":200,\"message\":\"ok\",\"data\":{}}").isSuccess());
        assertFailure(BusinessResponseParsers.emptyObject(
                "{\"code\":200,\"message\":\"ok\",\"data\":[]}"),
                ServerFailure.Kind.CONTRACT);

        String[] invalidData = {"null", "0", "\"\"", "[0]", "{\"extra\":0}"};
        for (String data : invalidData) {
            assertFailure(BusinessResponseParsers.empty(
                    "{\"code\":200,\"message\":\"ok\",\"data\":" + data + "}"),
                    ServerFailure.Kind.CONTRACT);
            assertFailure(BusinessResponseParsers.emptyObject(
                    "{\"code\":200,\"message\":\"ok\",\"data\":" + data + "}"),
                    ServerFailure.Kind.CONTRACT);
        }
        String missingData = "{\"code\":200,\"message\":\"ok\"}";
        assertFailure(BusinessResponseParsers.empty(missingData),
                ServerFailure.Kind.CONTRACT);
        assertFailure(BusinessResponseParsers.emptyObject(missingData),
                ServerFailure.Kind.CONTRACT);
        String rejected = "{\"code\":403,\"message\":\"denied\",\"data\":{}}";
        assertFailure(BusinessResponseParsers.empty(rejected),
                ServerFailure.Kind.REMOTE_REJECTED);
        assertFailure(BusinessResponseParsers.emptyObject(rejected),
                ServerFailure.Kind.REMOTE_REJECTED);
    }

    @Test
    public void assignedResponseRequiresOnlyTheDocumentedIdentifier() {
        ApiResult<AssignedCabinet> assigned = BusinessResponseParsers.userBoard(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"fc_id\":17}}" );
        assertTrue(assigned.isSuccess());
        assertEquals(17L, assigned.value().fcId());
    }

    @Test
    public void dtoConstructorsDefensivelyCopyCollections() {
        Map<Integer, List<ControlPanelPreview.Cabinet>> layers = new LinkedHashMap<>();
        layers.put(0, Collections.<ControlPanelPreview.Cabinet>emptyList());
        ControlPanelPreview preview = new ControlPanelPreview(
                Arrays.asList(1), Collections.<ControlPanelPreview.AllOpenCommand>emptyList(), layers);
        layers.clear();
        assertTrue(preview.layers().containsKey(0));
        try {
            preview.pages().add(2);
            fail("Expected immutable pages");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static String authFixture(String roleField, String checkStatus) {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"token\":\"session-token\"," + roleField + ","
                + "\"locker_check_status\":" + checkStatus + ",\"uid\":9}}";
    }

    private static String previewFixture(String extra) {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"page\":[1],\"all_open_command\":[]," + extra + "}}";
    }

    private static String usedCabinetFixture(
            String recordId, String fcId, String useTime) {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"user_name\":\"U\",\"mobile\":\"00123\",\"list\":[{"
                + "\"record_id\":" + recordId + ",\"fc_id\":" + fcId + ","
                + "\"name\":\"A012\",\"start_use\":\"2026-09-06 10:00:00\","
                + "\"use_time\":" + useTime + ",\"use_notice\":\"notice\"}]}}";
    }

    private static String stringNumericCabinetFixture() {
        return "\"fc_id\":\"7\",\"channel_id\":\"9\",\"cabinet_label\":\"007\","
                + "\"status\":\"0\",\"area_id\":\"2\",\"board_hex\":\"01\","
                + "\"channel_no\":\"00\",\"open_command\":\"8A 01 00 11 9A\",\"check_status\":0";
    }

    private static String cabinetFixture(long fcId, long channelId, String label,
            int status, long areaId, String boardHex, String channelNo,
            String openCommand, int checkStatus) {
        return "\"fc_id\":" + fcId + ",\"channel_id\":" + channelId
                + ",\"cabinet_label\":\"" + label + "\",\"status\":" + status
                + ",\"area_id\":" + areaId + ",\"board_hex\":\"" + boardHex
                + "\",\"channel_no\":\"" + channelNo + "\",\"open_command\":\""
                + openCommand + "\",\"check_status\":" + checkStatus;
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind kind) {
        assertFalse(result.isSuccess());
        assertEquals(kind, result.failure().kind());
    }

    private static void reject(Attempt attempt) {
        try {
            attempt.run();
            fail("Expected rejected input");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private interface Attempt { void run(); }
}
