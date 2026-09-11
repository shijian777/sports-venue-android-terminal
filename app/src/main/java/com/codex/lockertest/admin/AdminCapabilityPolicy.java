package com.codex.lockertest.admin;

public interface AdminCapabilityPolicy {
    boolean allows(AdminCapability capability, boolean online, boolean serverAuthorized);
}
