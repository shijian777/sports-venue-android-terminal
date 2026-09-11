package com.codex.lockertest.face.baidu.license;

/** Finite, administrator-safe helper result codes derived from the vendor sample. */
public final class CodeDetail {
    public static final int SUCCESS = 1000;
    public static final int FILE_READ_ERROR = 1001;
    public static final int FILE_CLEAN_ERROR = 1002;
    public static final int FILE_NOT_ERROR = 1005;
    public static final int CONTEXT_NOT_ERROR = 1006;
    public static final int DEVICES_ID_NOT_ERROR = 1007;
    public static final int JSON_CREATE_ERROR = 1008;
    public static final int HTTP_ERROR_ERROR = 1009;
    public static final int HTTP_RESULT_ERROR = 1010;
    public static final int WIFI_ERROR = 1011;
    public static final int LICENSE_INVALID = 1012;
    public static final int INTERNAL_ERROR = 1013;

    /* AndroidLicenser.ErrorCode ordinals in the pinned Baidu 8.5 AAR. */
    public static final int VENDOR_LICENSE_NOT_INITIALIZED = 1;
    public static final int VENDOR_LICENSE_DECRYPT_ERROR = 2;
    public static final int VENDOR_LICENSE_INFO_FORMAT_ERROR = 3;
    public static final int VENDOR_LICENSE_KEY_CHECK_ERROR = 4;
    public static final int VENDOR_LICENSE_ALGORITHM_CHECK_ERROR = 5;
    public static final int VENDOR_LICENSE_MD5_CHECK_ERROR = 6;
    public static final int VENDOR_LICENSE_DEVICE_ID_CHECK_ERROR = 7;
    public static final int VENDOR_LICENSE_PACKAGE_NAME_CHECK_ERROR = 8;
    public static final int VENDOR_LICENSE_EXPIRED_TIME_CHECK_ERROR = 9;
    public static final int VENDOR_LICENSE_FUNCTION_CHECK_ERROR = 10;
    public static final int VENDOR_LICENSE_TIME_EXPIRED = 11;
    public static final int VENDOR_LICENSE_LOCAL_FILE_ERROR = 12;
    public static final int VENDOR_LICENSE_REMOTE_DATA_ERROR = 13;
    public static final int VENDOR_LICENSE_LOCAL_TIME_ERROR = 14;
    public static final int VENDOR_LICENSE_PARAM_ERROR = 15;
    public static final int VENDOR_LICENSE_KEY_FILE_ERROR = 16;
    public static final int VENDOR_OTHER_ERROR = 17;

    /* Semantic activation service codes listed by the pinned Baidu demo. */
    public static final int REMOTE_NO_AUTH_TO_OPERATE = 290000;
    public static final int REMOTE_KEY_INVALID = 290002;
    public static final int REMOTE_DEVICE_ID_NOT_CORRECT = 290003;
    public static final int REMOTE_LICENSE_ACTIVE_ON_OTHER_DEVICE = 290004;
    public static final int REMOTE_LICENSE_TIMES_LIMIT = 290008;
    public static final int REMOTE_LICENSE_BOUND_TO_OTHER_DEVICE = 290009;

    /**
     * Strict allow-list for failures that conclusively mean this authorization
     * cannot be used on this app/device. Everything else remains retryable.
     */
    public static boolean isDeterministicAuthorizationInvalid(int code) {
        switch (code) {
            case LICENSE_INVALID:
            case VENDOR_LICENSE_DECRYPT_ERROR:
            case VENDOR_LICENSE_INFO_FORMAT_ERROR:
            case VENDOR_LICENSE_KEY_CHECK_ERROR:
            case VENDOR_LICENSE_ALGORITHM_CHECK_ERROR:
            case VENDOR_LICENSE_MD5_CHECK_ERROR:
            case VENDOR_LICENSE_DEVICE_ID_CHECK_ERROR:
            case VENDOR_LICENSE_PACKAGE_NAME_CHECK_ERROR:
            case VENDOR_LICENSE_EXPIRED_TIME_CHECK_ERROR:
            case VENDOR_LICENSE_FUNCTION_CHECK_ERROR:
            case VENDOR_LICENSE_TIME_EXPIRED:
            case VENDOR_LICENSE_PARAM_ERROR:
            case VENDOR_LICENSE_KEY_FILE_ERROR:
            case REMOTE_NO_AUTH_TO_OPERATE:
            case REMOTE_KEY_INVALID:
            case REMOTE_DEVICE_ID_NOT_CORRECT:
            case REMOTE_LICENSE_ACTIVE_ON_OTHER_DEVICE:
            case REMOTE_LICENSE_TIMES_LIMIT:
            case REMOTE_LICENSE_BOUND_TO_OTHER_DEVICE:
                return true;
            default:
                return false;
        }
    }

    /** Local-check-only codes which mean no persisted authorization exists. */
    public static boolean isMissingLocalAuthorization(int code) {
        return code == FILE_READ_ERROR || code == FILE_NOT_ERROR;
    }

    /** Manager-facing classification with local-cache absence scoped correctly. */
    public static boolean isAuthorizationInvalidResult(int code,
            boolean localCheck) {
        return isDeterministicAuthorizationInvalid(code)
                || (localCheck && isMissingLocalAuthorization(code));
    }

    private CodeDetail() {
    }
}
