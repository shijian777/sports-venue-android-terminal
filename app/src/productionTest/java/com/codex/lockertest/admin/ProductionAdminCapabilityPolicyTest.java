package com.codex.lockertest.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProductionAdminCapabilityPolicyTest {
    @Test
    public void productionOfflineAllowsDiagnosticsButNoBusinessMutation() {
        AdminCapabilityPolicy policy = AdminCapabilityAssembly.create();

        assertTrue(policy.allows(AdminCapability.VIEW_DIAGNOSTICS, false, false));
        assertTrue(policy.allows(AdminCapability.MANAGE_SERIAL_CONNECTION, false, false));
        assertFalse(policy.allows(AdminCapability.ACTIVATE_FACE_SDK, false, false));
        assertTrue(policy.allows(AdminCapability.ACTIVATE_FACE_SDK, true, false));
        assertFalse(policy.allows(AdminCapability.OPEN_LOCKER, false, false));
        assertFalse(policy.allows(AdminCapability.CLEAR_LOCKERS, true, false));
        assertFalse(policy.allows(AdminCapability.CHANGE_MAPPING, true, false));
        assertTrue(policy.allows(AdminCapability.OPEN_LOCKER, true, true));
    }

    @Test
    public void productionPhysicalAndConfigurationActionsNeedBothConditions() {
        AdminCapabilityPolicy policy = AdminCapabilityAssembly.create();

        for (AdminCapability capability : new AdminCapability[] {
                AdminCapability.OPEN_LOCKER,
                AdminCapability.CLEAR_LOCKERS,
                AdminCapability.CHANGE_MAPPING
        }) {
            assertFalse(policy.allows(capability, false, false));
            assertFalse(policy.allows(capability, false, true));
            assertFalse(policy.allows(capability, true, false));
            assertTrue(policy.allows(capability, true, true));
        }
    }

    @Test
    public void productionRejectsMissingCapability() {
        assertFalse(AdminCapabilityAssembly.create().allows(null, true, true));
    }
}
