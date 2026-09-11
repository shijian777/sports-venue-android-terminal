package com.codex.lockertest.face.verification;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.codex.lockertest.runtime.RuntimeSerialLog;

public final class FaceVerificationAssembly {
    private static final String PREFERENCE_FILE = "face_verification_local_demo";
    private static final String ENABLED_KEY = "local_demo_enabled";

    private final FaceVerificationEnvironment environment;
    private final FaceVerificationClient client;

    private FaceVerificationAssembly(FaceVerificationEnvironment environment,
            FaceVerificationClient client) {
        this.environment = environment;
        this.client = client;
    }

    public static FaceVerificationAssembly create(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        Context application = context.getApplicationContext();
        SharedPreferences preferences = application.getSharedPreferences(
                PREFERENCE_FILE, Context.MODE_PRIVATE);
        LocalDemoPreferenceStore store = new LocalDemoPreferenceStore(
                new LocalDemoPreferenceStore.BooleanStorage() {
                    @Override
                    public boolean loadDisabledByDefault() {
                        return preferences.getBoolean(ENABLED_KEY, false);
                    }

                    @Override
                    public void save(boolean enabled) {
                        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply();
                    }
                });
        FaceVerificationEnvironment.IssuerCapability issuerCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        FaceVerificationEnvironment environment = new FaceVerificationEnvironment(
                FaceVerificationSource.LOCAL_DEMO, true, store,
                FaceVerificationEnvironment::secureTicketId, issuerCapability);
        Handler handler = new Handler(Looper.getMainLooper());
        LocalPassFaceVerificationClient client = new LocalPassFaceVerificationClient(
                environment, issuerCapability,
                new LocalPassFaceVerificationClient.Clock() {
                    @Override
                    public long epochMillis() {
                        return System.currentTimeMillis();
                    }

                    @Override
                    public long elapsedMillis() {
                        return SystemClock.elapsedRealtime();
                    }
                },
                (task, delayMillis) -> {
                    if (!handler.postDelayed(task, delayMillis)) {
                        return null;
                    }
                    return () -> handler.removeCallbacks(task);
                },
                jpeg -> {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                    return new FaceJpegContract.Dimensions(options.outWidth, options.outHeight);
                },
                (requestId, stage, elapsedMillis, byteCount) ->
                        RuntimeSerialLog.shared().append(
                                "face request=" + requestId
                                        + " stage=" + stage
                                        + " elapsedMs=" + elapsedMillis
                                        + " bytes=" + byteCount),
                LocalPassFaceVerificationClient::secureDemoCredential);
        return new FaceVerificationAssembly(environment, client);
    }

    public FaceVerificationEnvironment environment() {
        return environment;
    }

    public FaceVerificationClient client() {
        return client;
    }
}
