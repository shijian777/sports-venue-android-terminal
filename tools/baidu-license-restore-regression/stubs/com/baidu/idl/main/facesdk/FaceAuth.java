package com.baidu.idl.main.facesdk;

import android.content.Context;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.license.BDFaceLicenseLocalInfo;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;

/** Fake only the native/network SDK boundary; the production adapter and policy remain real. */
public final class FaceAuth {
    public static String localKey;
    public static RuntimeException localFailure;
    public static String requestedKey;
    public static int localReads, requests;
    public static Callback pending;
    public static void reset() {
        localKey = null; localFailure = null; requestedKey = null;
        localReads = 0; requests = 0; pending = null;
    }
    public void setCoreConfigure(BDFaceSDKCommon.BDFaceCoreRunMode mode, int cores) { }
    public BDFaceLicenseLocalInfo getLocalInfo(Context context) {
        localReads++;
        if (localFailure != null) throw localFailure;
        if (localKey == null) return null;
        BDFaceLicenseLocalInfo info = new BDFaceLicenseLocalInfo();
        info.licenseKey = localKey;
        return info;
    }
    public void initLicenseOnLine(Context context, String key, Callback callback) {
        requests++; requestedKey = key; pending = callback;
    }
    public static void respond(int code, String message) {
        if (pending == null) throw new AssertionError("SDK was never invoked");
        pending.onResponse(code, message);
    }
}
