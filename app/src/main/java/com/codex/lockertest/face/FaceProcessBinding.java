package com.codex.lockertest.face;

import java.security.SecureRandom;
import java.util.Arrays;

/** Opaque nonce for binding a verification ticket to one application process. */
public final class FaceProcessBinding {
    private final String value;

    public FaceProcessBinding() {
        this(new SecureRandom());
    }

    FaceProcessBinding(SecureRandom random) {
        if (random == null) throw new IllegalArgumentException("random cannot be null");
        byte[] bytes = new byte[16];
        try {
            random.nextBytes(bytes);
            StringBuilder hex = new StringBuilder(32);
            for (byte item : bytes) {
                hex.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(item & 0x0f, 16));
            }
            value = hex.toString();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public String value() {
        return value;
    }
}
