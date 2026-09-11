package com.codex.lockertest.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalDemoAdminCapabilityPolicyTest {
    @Test
    public void localDemoPreservesEveryMaintenanceInteractionRegardlessOfServerState() {
        AdminCapabilityPolicy policy = AdminCapabilityAssembly.create();

        for (AdminCapability capability : AdminCapability.values()) {
            assertTrue(policy.allows(capability, false, false));
            assertTrue(policy.allows(capability, false, true));
            assertTrue(policy.allows(capability, true, false));
            assertTrue(policy.allows(capability, true, true));
        }
        assertFalse(policy.allows(null, true, true));
    }
}
