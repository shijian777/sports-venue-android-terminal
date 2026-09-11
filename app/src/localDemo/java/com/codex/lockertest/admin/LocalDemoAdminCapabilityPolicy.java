package com.codex.lockertest.admin;

/** Preserves all existing administrator interactions in the explicit demo variant. */
public final class LocalDemoAdminCapabilityPolicy implements AdminCapabilityPolicy {
    @Override
    public boolean allows(
            AdminCapability capability, boolean online, boolean serverAuthorized) {
        return capability != null;
    }
}
