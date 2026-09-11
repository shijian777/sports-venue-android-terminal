package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BusinessIdentityRejectionTest {
    @Test
    public void exactUserInfoNoEntryRejectionHasDedicatedPayloadFreeReason() {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(
                "{\"code\":400,\"message\":\"无进场记录\",\"data\":null}");
        assertRejected(result, "NO_ENTRY_RECORD");
    }

    @Test
    public void noEntryRejectionNeverExposesTokenEvenWithSuccessShapedData() {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(
                response("400", "\"无进场记录\"", customerData()));
        assertRejected(result, "NO_ENTRY_RECORD");
    }

    @Test
    public void nearMessagesAndOtherCodesRemainGenericRemoteRejections() {
        String[][] cases = {
                {"400", "\"无进场记录 \""},
                {"400", "\" 无进场记录\""},
                {"400", "\"无进场记录，请联系前台\""},
                {"400", "\"untrusted-credential-marker\""},
                {"400", "null"},
                {"400", "{\"message\":\"无进场记录\"}"},
                {"2", "\"无进场记录\""},
                {"401", "\"无进场记录\""},
                {"500", "\"无进场记录\""}
        };
        for (String[] entry : cases) {
            assertRejected(BusinessResponseParsers.userInfo(
                    response(entry[0], entry[1], customerData())), "REMOTE_REJECTED");
        }
    }

    @Test
    public void sameMessageOnOtherEndpointsIsNotAUserInfoNoEntryReason() {
        String body = response("400", "\"无进场记录\"", "null");
        assertRejected(BusinessResponseParsers.token(body), "REMOTE_REJECTED");
        assertRejected(BusinessResponseParsers.controlPanelPreview(body), "REMOTE_REJECTED");
        assertRejected(BusinessResponseParsers.useCabinetList(body), "REMOTE_REJECTED");
    }

    @Test
    public void successfulUserInfoStillUsesStrictDataContractNotMessageClassification() {
        assertTrue(BusinessResponseParsers.userInfo(
                response("200", "\"无进场记录\"", customerData())).isSuccess());
        assertRejected(BusinessResponseParsers.userInfo(
                response("200", "\"无进场记录\"", "null")), "CONTRACT");
        assertRejected(BusinessResponseParsers.userInfo(
                response("\"400\"", "\"无进场记录\"", "null")), "CONTRACT");
    }

    private static String response(String code, String message, String data) {
        return "{\"code\":" + code + ",\"message\":" + message + ",\"data\":" + data + "}";
    }

    private static String customerData() {
        return "{\"token\":\"test-only-secret\",\"user_type\":0,"
                + "\"locker_check_status\":0,\"uid\":1}";
    }

    private static void assertRejected(ApiResult<?> result, String reason) {
        assertFalse(result.isSuccess());
        assertEquals(reason, result.failure().kind().name());
        try {
            result.value();
            fail("A non-success response must never expose an authenticated value");
        } catch (IllegalStateException expected) {
            assertEquals("Failed API result has no value", expected.getMessage());
        }
    }
}
