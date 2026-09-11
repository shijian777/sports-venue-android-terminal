package com.codex.lockertest.face.baidu.license;

import android.content.Context;

import com.baidu.idl.main.facesdk.FaceAuth;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.license.BDFaceLicenseLocalInfo;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.idl.main.facesdk.utils.PreferencesUtil;

/** Thin process boundary around Baidu FaceAuth's supported public API. */
public final class BdFaceAuth {
    private static final String CONTEXT_MESSAGE = "授权环境不可用";
    private static final String OPERATION_MESSAGE = "百度人脸授权暂时不可用";

    private final OfficialFaceAuthOperation operation =
            new OfficialFaceAuthOperation();
    private volatile FaceAuth faceAuth;

    /** Manager-owned linearization gate for accepting a successful activation. */
    public interface CommitGate {
        boolean tryCommit();
    }

    /**
     * Recovers the SDK's saved online activation identifier before consulting
     * native local-info, which may be empty until authorization is initialized.
     * The saved identifier is never itself proof of authorization; FaceAuth validates it.
     */
    public void checkLocal(Context context, Callback callback) {
        requireCallback(callback);
        Context application = application(context);
        if (application == null) {
            respondSafely(callback, CodeDetail.CONTEXT_NOT_ERROR, CONTEXT_MESSAGE);
            return;
        }
        try {
            FaceAuth vendor = faceAuth();
            PreferencesUtil.initPrefs(application);
            String sdkSavedLicenseKey = PreferencesUtil.getString("activate_online_key", "");
            if (sdkSavedLicenseKey == null || sdkSavedLicenseKey.trim().length() == 0) {
                BDFaceLicenseLocalInfo localInfo = vendor.getLocalInfo(application);
                sdkSavedLicenseKey = localInfo == null ? null : localInfo.licenseKey;
            }
            operation.checkLocal(sdkSavedLicenseKey,
                    (licenseKey, result) -> vendor.initLicenseOnLine(application, licenseKey,
                            (code, ignoredVendorResponse) ->
                                    result.onResult(code, ignoredVendorResponse)),
                    (code, safeMessage) -> respondSafely(
                            callback, code, safeMessage));
            sdkSavedLicenseKey = null;
        } catch (RuntimeException | LinkageError failure) {
            respondSafely(callback, CodeDetail.INTERNAL_ERROR, OPERATION_MESSAGE);
        }
    }

    /** Performs first-time online activation through FaceAuth itself. */
    public void activateOnline(Context context, String activationValue,
            CommitGate commitGate, Callback callback) {
        requireCallback(callback);
        Context application = application(context);
        if (application == null) {
            respondSafely(callback, CodeDetail.CONTEXT_NOT_ERROR, CONTEXT_MESSAGE);
            return;
        }
        try {
            FaceAuth vendor = faceAuth();
            operation.activateOnline(activationValue,
                    commitGate == null ? null : commitGate::tryCommit,
                    (licenseKey, result) -> vendor.initLicenseOnLine(application, licenseKey,
                            (code, ignoredVendorResponse) ->
                                    result.onResult(code, ignoredVendorResponse)),
                    (code, safeMessage) -> respondSafely(
                            callback, code, safeMessage));
        } catch (RuntimeException | LinkageError failure) {
            respondSafely(callback, CodeDetail.INTERNAL_ERROR, OPERATION_MESSAGE);
        }
    }

    private FaceAuth faceAuth() {
        FaceAuth current = faceAuth;
        if (current != null) return current;
        synchronized (this) {
            current = faceAuth;
            if (current == null) {
                current = new FaceAuth();
                current.setCoreConfigure(
                        BDFaceSDKCommon.BDFaceCoreRunMode.BDFACE_LITE_POWER_NO_BIND, 2);
                faceAuth = current;
            }
            return current;
        }
    }

    private static void requireCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
    }

    private static Context application(Context context) {
        if (context == null) return null;
        try {
            return context.getApplicationContext();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static void respondSafely(Callback callback, int code,
            String safeMessage) {
        try {
            callback.onResponse(code, safeMessage);
        } catch (RuntimeException | LinkageError ignored) {
            // Callback isolation is part of the process boundary.
        }
    }
}
