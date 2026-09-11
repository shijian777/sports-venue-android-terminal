package com.baidu.idl.main.facesdk.utils;

import android.content.Context;

/** Fake only SDK-owned preference I/O; no file is read or written. */
public final class PreferencesUtil {
    public static String onlineKey;
    public static RuntimeException failure;
    public static int initialized;
    public static void reset() { onlineKey = ""; failure = null; initialized = 0; }
    public static void initPrefs(Context context) {
        if (context == null) throw new AssertionError("Missing application context");
        initialized++;
    }
    public static String getString(String key, String fallback) {
        if (initialized == 0) throw new AssertionError("SDK preferences read before initialization");
        if (!"activate_online_key".equals(key)) throw new AssertionError("Wrong SDK-owned preference key");
        if (failure != null) throw failure;
        return onlineKey == null ? fallback : onlineKey;
    }
}
