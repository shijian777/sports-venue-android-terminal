package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.ServerFailure;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Management-card wire migration must not weaken identity or customer validation. */
public final class ManagementCardDynamicCodeParserTest {
    @Test public void numericZeroSkipsOnlyTheAdditionalDynamicCode() {
        assertAdmin(admin("0"), false);
    }

    @Test public void numericOneRequiresTheAdditionalDynamicCode() {
        assertAdmin(admin("1"), true);
    }

    @Test public void stringZeroSkipsOnlyTheAdditionalDynamicCode() {
        assertAdmin(admin("\"0\""), false);
    }

    @Test public void stringOneRequiresTheAdditionalDynamicCode() {
        assertAdmin(admin("\"1\""), true);
    }

    @Test public void administratorRoleAliasesKeepTheSameGate() {
        assertAdmin(admin("\"1\"").replace("\"usr_type\":1", "\"user_type\":1"), true);
        reject(admin("0").replace("\"usr_type\":1", "\"usr_type\":1,\"user_type\":1"));
    }

    @Test public void missingOrLegacyAdminFlagMustNotDefaultToNoVerification() {
        reject(admin("0").replace(",\"dynamic_verification_code\":0", ""));
        reject(admin("0").replace("dynamic_verification_code", "locker_check_status"));
    }

    @Test public void invalidAdminFlagsCannotCreateAnAuthenticatedIdentity() {
        String[] invalid = {"null", "true", "false", "{}", "{\"11\":0}", "[]",
                "-1", "2", "-0", "0.0", "1.0", "1e0", "\"\"", "\" \"", "\"00\"",
                "\"01\"", "\" 0\"", "\"1 \"", "\"+1\"", "\"-1\"", "\"2\"", "\"0.0\""};
        for (String value : invalid) reject(admin(value));
    }

    @Test public void bothFlagsAndUnknownFieldsAreRejectedInsteadOfChoosingOne() {
        reject(admin("0").replace("\"uid\":9", "\"locker_check_status\":1,\"uid\":9"));
        reject(admin("1").replace("\"uid\":9", "\"locker_check_status\":0,\"uid\":9"));
        reject(admin("0").replace("\"uid\":9", "\"extra\":0,\"uid\":9"));
    }

    @Test public void duplicateDynamicFlagIsRejected() {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(
                admin("0").replace("\"uid\":9", "\"dynamic_verification_code\":1,\"uid\":9"));
        assertFalse(result.isSuccess());
        assertEquals(ServerFailure.Kind.INVALID_JSON, result.failure().kind());
    }

    @Test public void validAdminFlagDoesNotBypassTokenRoleOrUidValidation() {
        for (String token : new String[] {"null", "\"\"", "\" \"", "1", "{}"}) {
            reject(admin("0").replace("\"test-session\"", token));
        }
        reject(admin("0").replace("\"token\":\"test-session\",", ""));
        for (String role : new String[] {"null", "\"1\"", "2", "-1", "{}"}) {
            reject(admin("0").replace("\"usr_type\":1", "\"usr_type\":" + role));
        }
        reject(admin("0").replace("\"uid\":9", "\"uid\":\"9\""));
        reject(admin("0").replace("\"uid\":9", "\"uid\":-1"));
    }

    @Test public void ordinaryMemberKeepsLegacyNumericFieldAndCustomerState() {
        for (String flag : new String[] {"0", "1"}) {
            String body = admin(flag).replace("\"usr_type\":1", "\"usr_type\":0")
                    .replace("dynamic_verification_code", "locker_check_status");
            ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(body);
            assertTrue(result.isSuccess());
            assertEquals(UserType.USER, result.value().userType());
            assertTrue(result.value().isCustomerReady());
            assertFalse(result.value().dynamicCodeRequired());
        }
    }

    private static String admin(String flag) {
        return "{\"code\":200,\"message\":\"success\",\"data\":{"
                + "\"token\":\"test-session\",\"usr_type\":1,"
                + "\"dynamic_verification_code\":" + flag + ",\"uid\":9}}";
    }

    private static void assertAdmin(String body, boolean dynamicRequired) {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(body);
        assertTrue("Expected accepted management-card response", result.isSuccess());
        assertEquals(UserType.ADMIN, result.value().userType());
        assertEquals(dynamicRequired, result.value().dynamicCodeRequired());
        assertFalse("Admin must never be routed as an ordinary customer", result.value().isCustomerReady());
        assertEquals("test-session", result.value().token().value());
    }

    private static void reject(String body) {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(body);
        assertFalse("Malformed identity must not be accepted", result.isSuccess());
        assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
    }
}
