package com.codex.lockertest.admin;

public final class AdminCapabilityAssembly {
    private AdminCapabilityAssembly() { }

    public static AdminCapabilityPolicy create() {
        return new LocalDemoAdminCapabilityPolicy();
    }
}
