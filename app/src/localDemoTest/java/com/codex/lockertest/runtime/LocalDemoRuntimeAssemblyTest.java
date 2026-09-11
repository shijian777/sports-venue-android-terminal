package com.codex.lockertest.runtime;

import com.codex.lockertest.model.UnlockMethod;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class LocalDemoRuntimeAssemblyTest {
    @Test
    public void localDemoAssemblyUsesAllDemoPoliciesAndFeatureFlags() {
        RuntimeServices services = RuntimeAssembly.create(null);

        assertTrue(services.localDemo());
        assertTrue(services.credentialPolicy() instanceof LocalDemoCredentialAdmissionPolicy);
        assertTrue(services.adminPolicy() instanceof LocalDemoAdminCredentialPolicy);
        assertTrue(services.unlockAuthorizer() instanceof LocalDemoCustomerUnlockAuthorizer);
        assertTrue(services.layoutPolicy() instanceof LocalDemoInitialLayoutPolicy);
        assertSame(DemoFeatureFlags.defaults(), services.featureAvailability());
    }

    @Test
    public void localDemoAssemblyProvidesItsCompatibilityLayoutAndFeatureAvailability() {
        RuntimeServices services = RuntimeAssembly.create(null);

        assertNotNull(services.layoutPolicy().initialLayout());
        assertTrue(services.featureAvailability().isEnabled(UnlockMethod.PHONE));
        assertTrue(services.featureAvailability().isEnabled(UnlockMethod.PASSWORD));
        assertTrue(services.featureAvailability().isEnabled(UnlockMethod.ID_CARD));
        assertFalse(services.featureAvailability().isEnabled(UnlockMethod.FACE));
    }

    @Test
    public void localDemoAssemblyProvidesTheVisibleExplicitFaceWarning() {
        FaceBannerPolicy policy = RuntimeAssembly.createFaceBannerPolicy();

        assertTrue(policy.visible());
        assertEquals("本机联调：未进行身份比对", policy.text());
    }
}
