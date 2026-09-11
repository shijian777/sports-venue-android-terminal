package com.codex.lockertest.face;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Derives an opaque, domain-separated device binding without exposing Android ID. */
public final class FaceDeviceBindingProvider {
    private static final String DOMAIN = "face-device-binding-v1";

    public static final class Binding {
        private static final Binding UNAVAILABLE = new Binding(null);
        private final String value;

        private Binding(String value) { this.value = value; }

        public boolean isAvailable() { return value != null; }
        public String value() { return value; }
    }

    private FaceDeviceBindingProvider() {
    }

    public static Binding create(Context context) {
        if (context == null) return Binding.UNAVAILABLE;
        try {
            Context application = context.getApplicationContext();
            if (application == null) return Binding.UNAVAILABLE;
            String packageName = application.getPackageName();
            String localId = Settings.Secure.getString(
                    application.getContentResolver(), Settings.Secure.ANDROID_ID);
            return digest(packageName, localId);
        } catch (RuntimeException | LinkageError failure) {
            return Binding.UNAVAILABLE;
        }
    }

    static Binding digest(String applicationId, String localId) {
        if (isBlank(applicationId) || isBlank(localId)) return Binding.UNAVAILABLE;
        MessageDigest messageDigest = null;
        byte[] digestBytes = null;
        byte[] domainBytes = null;
        byte[] applicationBytes = null;
        byte[] localBytes = null;
        try {
            domainBytes = DOMAIN.getBytes(StandardCharsets.UTF_8);
            applicationBytes = applicationId.getBytes(StandardCharsets.UTF_8);
            localBytes = localId.getBytes(StandardCharsets.UTF_8);
            messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(domainBytes);
            messageDigest.update((byte) 0);
            messageDigest.update(applicationBytes);
            messageDigest.update((byte) 0);
            messageDigest.update(localBytes);
            digestBytes = messageDigest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digestBytes) {
                hex.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(item & 0x0f, 16));
            }
            return new Binding(hex.toString());
        } catch (Exception | LinkageError failure) {
            return Binding.UNAVAILABLE;
        } finally {
            if (digestBytes != null) Arrays.fill(digestBytes, (byte) 0);
            if (domainBytes != null) Arrays.fill(domainBytes, (byte) 0);
            if (applicationBytes != null) Arrays.fill(applicationBytes, (byte) 0);
            if (localBytes != null) Arrays.fill(localBytes, (byte) 0);
            if (messageDigest != null) messageDigest.reset();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }
}
