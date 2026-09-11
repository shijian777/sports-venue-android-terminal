package com.codex.lockertest.runtime;

/** Variant-selected customer banner content for face and locker pages. */
public interface FaceBannerPolicy {
    CharSequence text();

    boolean visible();
}
