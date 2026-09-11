package com.codex.lockertest.face.baidu.license;

import android.content.Context;
import com.baidu.idl.main.facesdk.FaceAuth;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real production BdFaceAuth and OfficialFaceAuthOperation against external I/O fixtures. */
public final class BdFaceAuthRestoreTest {
    private final List<Integer> codes = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    @Before public void resetBoundaries() { FaceAuth.reset(); PreferencesUtil.reset(); }
    private void check() { new BdFaceAuth().checkLocal(new Context(), (code, message) -> {
        codes.add(code); messages.add(message);
    }); }

    @Test public void persistedOnlineKeyRestoresEvenWhenColdLocalInfoIsEmpty() {
        PreferencesUtil.onlineKey = "FIXTURE_ONLINE_KEY";
        check();
        assertEquals("SDK restore must start instead of reporting missing local authorization", 1, FaceAuth.requests);
        assertEquals("FIXTURE_ONLINE_KEY", FaceAuth.requestedKey);
        assertEquals("Online restore must not ask uninitialized native local info", 0, FaceAuth.localReads);
        assertTrue("A saved key alone must never authorize", codes.isEmpty());
        FaceAuth.respond(0, "vendor response");
        assertEquals(Integer.valueOf(0), codes.get(0));
    }

    @Test public void existingOnlineKeyAvoidsFailingLocalInfoProbe() {
        PreferencesUtil.onlineKey = "FIXTURE_ONLINE_KEY";
        FaceAuth.localFailure = new IllegalStateException("SENSITIVE_LOCAL_DETAIL");
        check();
        assertEquals(1, FaceAuth.requests);
        assertEquals(0, FaceAuth.localReads);
        assertTrue(codes.isEmpty());
    }

    @Test public void sdkRejectionDoesNotRetryAnotherKeyOrAuthorize() {
        PreferencesUtil.onlineKey = "FIXTURE_ONLINE_KEY";
        FaceAuth.localKey = "FIXTURE_DIFFERENT_LOCAL_KEY";
        check();
        assertEquals("FIXTURE_ONLINE_KEY", FaceAuth.requestedKey);
        FaceAuth.respond(CodeDetail.VENDOR_LICENSE_KEY_CHECK_ERROR, "SENSITIVE_VENDOR_DETAIL");
        assertEquals(1, FaceAuth.requests);
        assertEquals(0, FaceAuth.localReads);
        assertEquals(Integer.valueOf(CodeDetail.VENDOR_LICENSE_KEY_CHECK_ERROR), codes.get(0));
        assertFalse(messages.get(0).contains("SENSITIVE"));
    }

    @Test public void sdkNetworkFailureRemainsUnavailableWithoutLocalFallback() {
        PreferencesUtil.onlineKey = "FIXTURE_ONLINE_KEY";
        FaceAuth.localKey = "FIXTURE_DIFFERENT_LOCAL_KEY";
        check();
        FaceAuth.respond(-1, "SENSITIVE_NETWORK_DETAIL");
        assertEquals(Integer.valueOf(-1), codes.get(0));
        assertEquals(1, FaceAuth.requests);
        assertEquals(0, FaceAuth.localReads);
        assertFalse(CodeDetail.isAuthorizationInvalidResult(codes.get(0), true));
        assertFalse(messages.get(0).contains("SENSITIVE"));
    }

    @Test public void absentOnlineKeyKeepsExistingLocalInfoCompatibility() {
        FaceAuth.localKey = "FIXTURE_LOCAL_KEY";
        check();
        assertEquals("FIXTURE_LOCAL_KEY", FaceAuth.requestedKey);
        assertEquals(1, FaceAuth.localReads);
        assertTrue(codes.isEmpty());
        FaceAuth.respond(0, "vendor response");
        assertEquals(Integer.valueOf(0), codes.get(0));
    }

    @Test public void whitespaceOnlyOnlineKeyStillTriesLocalInfo() {
        PreferencesUtil.onlineKey = " \t\n";
        FaceAuth.localKey = "FIXTURE_LOCAL_KEY";
        check();
        assertEquals("FIXTURE_LOCAL_KEY", FaceAuth.requestedKey);
        assertEquals(1, FaceAuth.localReads);
    }

    @Test public void missingBothSourcesFailsWithoutCallingSdk() {
        check();
        assertEquals(0, FaceAuth.requests);
        assertEquals(1, FaceAuth.localReads);
        assertEquals(Integer.valueOf(CodeDetail.FILE_READ_ERROR), codes.get(0));
    }

    @Test public void preferenceReadFailureIsUnavailableAndDoesNotLeakOrSwitchKeys() {
        PreferencesUtil.failure = new IllegalStateException("SENSITIVE_PREFERENCE_DETAIL");
        FaceAuth.localKey = "FIXTURE_LOCAL_KEY";
        check();
        assertEquals(0, FaceAuth.requests);
        assertEquals(0, FaceAuth.localReads);
        assertEquals(Integer.valueOf(CodeDetail.INTERNAL_ERROR), codes.get(0));
        assertFalse(messages.get(0).contains("SENSITIVE"));
    }

    @Test public void oversizedOnlineKeyFailsClosedWithoutTryingAnotherKey() {
        PreferencesUtil.onlineKey = new String(new char[4097]).replace('\0', 'x');
        FaceAuth.localKey = "FIXTURE_LOCAL_KEY";
        check();
        assertEquals(0, FaceAuth.requests);
        assertEquals(0, FaceAuth.localReads);
        assertEquals(1, codes.size());
        assertNotEquals(Integer.valueOf(0), codes.get(0));
    }

    @Test public void duplicateVendorResponsesCannotChangeFirstAuthorizationResult() {
        PreferencesUtil.onlineKey = "FIXTURE_ONLINE_KEY";
        check();
        assertEquals(1, FaceAuth.requests);
        FaceAuth.respond(0, "first");
        FaceAuth.respond(CodeDetail.VENDOR_LICENSE_KEY_CHECK_ERROR, "late");
        assertEquals(1, codes.size());
        assertEquals(Integer.valueOf(0), codes.get(0));
    }

    @Test public void missingApplicationContextDoesNotReadSavedKeys() {
        new BdFaceAuth().checkLocal(null, (code, message) -> codes.add(code));
        assertEquals(Integer.valueOf(CodeDetail.CONTEXT_NOT_ERROR), codes.get(0));
        assertEquals(0, PreferencesUtil.initialized);
        assertEquals(0, FaceAuth.localReads);
        assertEquals(0, FaceAuth.requests);
    }
}
