package com.codex.lockertest.runtime;

/** Keeps local maintenance access unavailable until installation provisions a credential. */
public final class UnprovisionedAdminCredentialPolicy implements AdminCredentialPolicy {
    @Override
    public int requiredLength() {
        return 6;
    }

    @Override
    public boolean matches(char[] candidate) {
        return false;
    }

    @Override
    public String unavailableMessage() {
        return "维护密码尚未配置，请联系安装人员";
    }
}
