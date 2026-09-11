package com.codex.lockertest.runtime;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

public final class DemoFeatureFlags implements FeatureAvailability {
    private static final DemoFeatureFlags DEFAULTS = new DemoFeatureFlags();

    private DemoFeatureFlags() {
    }

    public static DemoFeatureFlags defaults() {
        return DEFAULTS;
    }

    @Override
    public boolean isEnabled(UnlockMethod method) {
        return method == UnlockMethod.PHONE
                || method == UnlockMethod.PASSWORD
                || method == UnlockMethod.ID_CARD;
    }
}
