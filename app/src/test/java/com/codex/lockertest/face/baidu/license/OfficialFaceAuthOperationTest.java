package com.codex.lockertest.face.baidu.license;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfficialFaceAuthOperationTest {
    @Test
    public void cachedActivationUsesTheSdkSavedLicenseKey() {
        FakeVendor vendor = new FakeVendor();
        List<Integer> results = new ArrayList<Integer>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.checkLocal("sdk-saved-key", vendor,
                (code, message) -> results.add(code));

        assertEquals("sdk-saved-key", vendor.requestedKey);
        vendor.respond(0, "vendor success");
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(0), results.get(0));
    }

    @Test
    public void missingCachedActivationFailsWithoutCallingTheSdk() {
        FakeVendor vendor = new FakeVendor();
        List<Integer> results = new ArrayList<Integer>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.checkLocal("  ", vendor,
                (code, message) -> results.add(code));

        assertEquals(null, vendor.requestedKey);
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(CodeDetail.FILE_READ_ERROR), results.get(0));
    }

    @Test
    public void onlineSuccessMustWinTheManagerCommitGate() {
        FakeVendor vendor = new FakeVendor();
        AtomicInteger commitAttempts = new AtomicInteger();
        List<Integer> results = new ArrayList<Integer>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.activateOnline("operator-entered-key", () -> {
            commitAttempts.incrementAndGet();
            return false;
        }, vendor, (code, message) -> results.add(code));

        vendor.respond(0, "vendor success");
        assertEquals(1, commitAttempts.get());
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(CodeDetail.INTERNAL_ERROR), results.get(0));
    }

    @Test
    public void vendorFailureDoesNotClaimTheManagerCommitGate() {
        FakeVendor vendor = new FakeVendor();
        AtomicInteger commitAttempts = new AtomicInteger();
        List<Integer> results = new ArrayList<Integer>();
        List<String> messages = new ArrayList<String>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.activateOnline("operator-entered-key", () -> {
            commitAttempts.incrementAndGet();
            return true;
        }, vendor, (code, message) -> {
            results.add(code);
            messages.add(message);
        });

        vendor.respond(37, "vendor secret must not escape");
        assertEquals(0, commitAttempts.get());
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(37), results.get(0));
        assertEquals("百度人脸授权暂时不可用", messages.get(0));
        assertFalse(messages.get(0).contains("secret"));
    }

    @Test
    public void localReauthorizationPreservesTransientVendorCodeForRetryClassification() {
        FakeVendor vendor = new FakeVendor();
        List<Integer> results = new ArrayList<Integer>();
        List<String> messages = new ArrayList<String>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.checkLocal("sdk-saved-key", vendor, (code, message) -> {
            results.add(code);
            messages.add(message);
        });

        vendor.respond(-1, "network stack detail must not escape");
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(-1), results.get(0));
        assertEquals("百度人脸授权暂时不可用", messages.get(0));
        assertFalse(messages.get(0).contains("network"));
    }

    @Test
    public void localReauthorizationPreservesDeterministicInvalidVendorCode() {
        FakeVendor vendor = new FakeVendor();
        List<Integer> results = new ArrayList<Integer>();
        List<String> messages = new ArrayList<String>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.checkLocal("sdk-saved-key", vendor, (code, message) -> {
            results.add(code);
            messages.add(message);
        });

        vendor.respond(CodeDetail.VENDOR_LICENSE_KEY_CHECK_ERROR,
                "vendor key detail must not escape");
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(CodeDetail.VENDOR_LICENSE_KEY_CHECK_ERROR),
                results.get(0));
        assertEquals("百度人脸授权信息无效", messages.get(0));
        assertFalse(messages.get(0).contains("vendor"));
    }

    @Test
    public void invalidWhitelistExcludesNetworkStorageClockAndUnknownFailures() {
        assertTrue(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_DECRYPT_ERROR));
        assertTrue(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_TIME_EXPIRED));
        assertTrue(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.REMOTE_KEY_INVALID));
        assertTrue(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.REMOTE_LICENSE_BOUND_TO_OTHER_DEVICE));

        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(-1));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_NOT_INITIALIZED));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_LOCAL_FILE_ERROR));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_REMOTE_DATA_ERROR));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.VENDOR_LICENSE_LOCAL_TIME_ERROR));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(
                CodeDetail.WIFI_ERROR));
        assertFalse(CodeDetail.isDeterministicAuthorizationInvalid(37));
    }

    @Test
    public void managerClassificationTreatsOnlyMissingLocalCacheAsInvalid() {
        assertTrue(CodeDetail.isAuthorizationInvalidResult(
                CodeDetail.FILE_READ_ERROR, true));
        assertTrue(CodeDetail.isAuthorizationInvalidResult(
                CodeDetail.FILE_NOT_ERROR, true));
        assertFalse(CodeDetail.isAuthorizationInvalidResult(
                CodeDetail.FILE_READ_ERROR, false));
        assertFalse(CodeDetail.isAuthorizationInvalidResult(-1, true));
        assertFalse(CodeDetail.isAuthorizationInvalidResult(
                CodeDetail.VENDOR_LICENSE_LOCAL_FILE_ERROR, true));
    }

    @Test
    public void duplicateVendorCallbacksReachTheManagerOnlyOnce() {
        FakeVendor vendor = new FakeVendor();
        List<Integer> results = new ArrayList<Integer>();
        OfficialFaceAuthOperation operation = new OfficialFaceAuthOperation();

        operation.checkLocal("sdk-saved-key", vendor,
                (code, message) -> results.add(code));

        vendor.respond(0, "first");
        vendor.respond(51, "late duplicate");
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(0), results.get(0));
    }

    private static final class FakeVendor
            implements OfficialFaceAuthOperation.VendorInvoker {
        private String requestedKey;
        private OfficialFaceAuthOperation.ResultCallback callback;

        @Override
        public void initLicenseOnLine(String licenseKey,
                OfficialFaceAuthOperation.ResultCallback callback) {
            this.requestedKey = licenseKey;
            this.callback = callback;
        }

        void respond(int code, String message) {
            callback.onResult(code, message);
        }
    }
}
