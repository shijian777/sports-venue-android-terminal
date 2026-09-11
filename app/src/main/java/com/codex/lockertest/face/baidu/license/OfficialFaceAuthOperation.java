package com.codex.lockertest.face.baidu.license;

/**
 * Pure policy around the vendor's official online-authorization entry point.
 * Android and Baidu SDK objects stay in {@link BdFaceAuth}; this class owns
 * validation, one-shot callback delivery and manager commit linearization.
 */
public final class OfficialFaceAuthOperation {
    private static final int MAX_LICENSE_CHARACTERS = 4096;
    private static final String SUCCESS_MESSAGE = "授权成功";
    private static final String LOCAL_MISSING_MESSAGE = "未找到本机百度授权";
    private static final String INVALID_MESSAGE = "百度人脸授权信息无效";
    private static final String OPERATION_MESSAGE = "百度人脸授权暂时不可用";

    public interface ResultCallback {
        void onResult(int code, String safeMessage);
    }

    public interface VendorInvoker {
        void initLicenseOnLine(String licenseKey, ResultCallback callback);
    }

    public interface CommitGate {
        boolean tryCommit();
    }

    public void checkLocal(String sdkSavedLicenseKey, VendorInvoker vendor,
            ResultCallback callback) {
        requireCallback(callback);
        OnceResult result = new OnceResult(callback);
        if (vendor == null) {
            result.respond(CodeDetail.INTERNAL_ERROR, OPERATION_MESSAGE);
            return;
        }
        if (isBlankOrTooLong(sdkSavedLicenseKey)) {
            result.respond(CodeDetail.FILE_READ_ERROR, LOCAL_MISSING_MESSAGE);
            return;
        }
        try {
            vendor.initLicenseOnLine(sdkSavedLicenseKey, (code, ignoredVendorMessage) -> {
                if (code == 0) {
                    result.respond(0, SUCCESS_MESSAGE);
                } else {
                    result.respond(code, safeFailureMessage(code));
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            result.respond(CodeDetail.INTERNAL_ERROR, OPERATION_MESSAGE);
        }
    }

    public void activateOnline(String activationValue, CommitGate commitGate,
            VendorInvoker vendor, ResultCallback callback) {
        requireCallback(callback);
        OnceResult result = new OnceResult(callback);
        if (vendor == null || commitGate == null
                || isBlankOrTooLong(activationValue)) {
            result.respond(CodeDetail.LICENSE_INVALID, INVALID_MESSAGE);
            return;
        }
        try {
            vendor.initLicenseOnLine(activationValue, (code, ignoredVendorMessage) -> {
                if (code != 0) {
                    result.respond(code, safeFailureMessage(code));
                    return;
                }
                boolean committed;
                try {
                    committed = commitGate.tryCommit();
                } catch (RuntimeException | LinkageError failure) {
                    committed = false;
                }
                result.respond(committed ? 0 : CodeDetail.INTERNAL_ERROR,
                        committed ? SUCCESS_MESSAGE : OPERATION_MESSAGE);
            });
        } catch (RuntimeException | LinkageError failure) {
            result.respond(CodeDetail.INTERNAL_ERROR, OPERATION_MESSAGE);
        }
    }

    private static void requireCallback(ResultCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
    }

    private static boolean isBlankOrTooLong(String value) {
        if (value == null || value.length() == 0
                || value.length() > MAX_LICENSE_CHARACTERS) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > ' ') return false;
        }
        return true;
    }

    private static String safeFailureMessage(int code) {
        return CodeDetail.isDeterministicAuthorizationInvalid(code)
                ? INVALID_MESSAGE : OPERATION_MESSAGE;
    }

    private static final class OnceResult {
        private ResultCallback callback;

        OnceResult(ResultCallback callback) {
            this.callback = callback;
        }

        void respond(int code, String safeMessage) {
            ResultCallback target;
            synchronized (this) {
                target = callback;
                if (target == null) return;
                callback = null;
            }
            try {
                target.onResult(code, safeMessage);
            } catch (RuntimeException | LinkageError ignored) {
                // Isolate the vendor/process boundary from UI callbacks.
            }
        }
    }
}
