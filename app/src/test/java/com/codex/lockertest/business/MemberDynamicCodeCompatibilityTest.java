package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.ServerFailure;
import org.junit.Test;

import static org.junit.Assert.*;

/** Wire aliases must not change the authenticated role or bypass validation. */
public final class MemberDynamicCodeCompatibilityTest {
    @Test public void newNumericFlagAllowsAuthenticatedMemberWithoutPromotingRole() {
        assertMember(member("dynamic_verification_code", "0"), 0);
        assertMember(member("dynamic_verification_code", "1"), 1);
    }

    @Test public void newStringFlagAllowsAuthenticatedMemberWithoutPromotingRole() {
        assertMember(member("dynamic_verification_code", "\"0\""), 0);
        assertMember(member("dynamic_verification_code", "\"1\""), 1);
    }

    @Test public void legacyNumericAndStringFlagsKeepExistingCustomerBehavior() {
        for (String field : new String[] {"locker_check_status", "dynamic_verification_code"}) {
            assertMember(member(field, "0"), 0);
            assertMember(member(field, "1"), 1);
            assertMember(member(field, "\"0\""), 0);
            assertMember(member(field, "\"1\""), 1);
        }
    }

    @Test public void eitherDocumentedRoleSpellingRetainsMemberIdentity() {
        for (String field : new String[] {"locker_check_status", "dynamic_verification_code"}) {
            assertMember(member(field, "1").replace("usr_type", "user_type"), 1);
            reject(member(field, "0").replace("\"usr_type\":0", "\"usr_type\":0,\"user_type\":0"));
        }
    }

    @Test public void missingDualConflictingOrUnknownFlagsNeverDefaultToSuccess() {
        String body = member("dynamic_verification_code", "0");
        reject(body.replace(",\"dynamic_verification_code\":0", ""));
        reject(body.replace("dynamic_verification_code", "unknown_check"));
        reject(body.replace("\"uid\":690", "\"locker_check_status\":0,\"uid\":690"));
        reject(body.replace("\"uid\":690", "\"locker_check_status\":1,\"uid\":690"));
        reject(body.replace("\"uid\":690", "\"extra\":0,\"uid\":690"));
    }

    @Test public void malformedValuesAreRejectedForBothAliases() {
        String[] invalid = {"null", "true", "false", "{}", "[]", "-1", "2", "-0",
                "0.0", "1.0", "1e0", "\"\"", "\" \"", "\"00\"", "\"01\"",
                "\" 0\"", "\"1 \"", "\"+1\"", "\"-1\"", "\"2\"", "\"0.0\""};
        for (String field : new String[] {"locker_check_status", "dynamic_verification_code"}) {
            for (String value : invalid) reject(member(field, value));
        }
    }

    @Test public void duplicateAliasIsInvalidJsonRatherThanLastValueWinning() {
        for (String field : new String[] {"locker_check_status", "dynamic_verification_code"}) {
            ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(
                    member(field, "0").replace("\"uid\":690", "\"" + field + "\":1,\"uid\":690"));
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.INVALID_JSON, result.failure().kind());
        }
    }

    @Test public void recognizedFlagsCannotBypassTokenRoleOrUidValidation() {
        for (String field : new String[] {"locker_check_status", "dynamic_verification_code"}) {
            String body = member(field, "0");
            for (String token : new String[] {"null", "\"\"", "\" \"", "1", "{}"}) {
                reject(body.replace("\"member-session\"", token));
            }
            reject(body.replace("\"token\":\"member-session\",", ""));
            for (String role : new String[] {"null", "\"0\"", "2", "-1", "{}"}) {
                reject(body.replace("\"usr_type\":0", "\"usr_type\":" + role));
            }
            reject(body.replace("\"uid\":690", "\"uid\":\"690\""));
            reject(body.replace("\"uid\":690", "\"uid\":-1"));
        }
    }

    private static String member(String field, String flag) {
        return "{\"code\":200,\"message\":\"success\",\"data\":{"
                + "\"token\":\"member-session\",\"usr_type\":0,\"" + field + "\":"
                + flag + ",\"uid\":690}}";
    }

    private static void assertMember(String body, int flag) {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(body);
        assertTrue("Valid member response must be accepted", result.isSuccess());
        assertEquals(UserType.USER, result.value().userType());
        assertTrue(result.value().isCustomerReady());
        assertFalse(result.value().dynamicCodeRequired());
        assertEquals("member-session", result.value().token().value());
        assertEquals(690, result.value().uid());
        assertEquals(flag, result.value().lockerCheckStatus());
    }

    private static void reject(String body) {
        ApiResult<AuthenticatedUser> result = BusinessResponseParsers.userInfo(body);
        assertFalse("Malformed member response must not grant access", result.isSuccess());
        assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
    }
}
