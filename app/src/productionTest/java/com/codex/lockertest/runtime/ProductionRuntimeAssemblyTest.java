package com.codex.lockertest.runtime;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ProductionRuntimeAssemblyTest {
    private static final String CREDENTIAL_UNAVAILABLE = "服务器认证尚未配置";
    private static final String UNLOCK_UNAVAILABLE = "服务器开柜协议未配置";
    private static final String ADMIN_UNAVAILABLE = "维护密码尚未配置，请联系安装人员";

    @Test
    public void productionAssemblySuppliesOnlyFailClosedRuntimeDependencies() {
        RuntimeServices services = RuntimeAssembly.create(null);

        assertFalse(services.localDemo());
        assertTrue(services.credentialPolicy()
                instanceof RejectingCredentialAdmissionPolicy);
        assertTrue(services.adminPolicy()
                instanceof UnprovisionedAdminCredentialPolicy);
        assertTrue(services.unlockAuthorizer()
                instanceof FailClosedCustomerUnlockAuthorizer);
        assertTrue(services.layoutPolicy() instanceof EmptyInitialLayoutPolicy);
        assertNotNull(services.featureAvailability());
    }

    @Test
    public void productionCredentialAdmissionRejectsEveryMethodAndInput() {
        RuntimeServices services = RuntimeAssembly.create(null);

        for (UnlockMethod method : UnlockMethod.values()) {
            assertRejected(services, method, "opaque-production-credential");
            assertRejected(services, method, null);
        }
        assertRejected(services, null, "opaque-production-credential");
    }

    @Test
    public void productionUnlockAuthorizationNeverIssuesARequest() {
        RuntimeServices services = RuntimeAssembly.create(null);

        for (UnlockMethod method : UnlockMethod.values()) {
            assertUnavailable(services, method, "opaque-production-credential", target());
            assertUnavailable(services, method, null, null);
        }
        assertUnavailable(services, null, null, null);
    }

    @Test
    public void productionAdminAndLayoutPoliciesAreUnavailableWithoutConfiguration() {
        RuntimeServices services = RuntimeAssembly.create(null);

        assertEquals(6, services.adminPolicy().requiredLength());
        assertFalse(services.adminPolicy().matches(new char[] {'1', '2', '3', '4', '5', '6'}));
        assertFalse(services.adminPolicy().matches(null));
        assertEquals(ADMIN_UNAVAILABLE, services.adminPolicy().unavailableMessage());
        assertNull(services.layoutPolicy().initialLayout());
        assertNull(services.layoutPolicy().layoutForDiscovery(null));
        assertNull(services.layoutPolicy().layoutForDiscovery(Collections.singletonList(LockerZone.A)));
    }

    @Test
    public void productionDisablesEveryCustomerUnlockMethod() {
        RuntimeServices services = RuntimeAssembly.create(null);

        for (UnlockMethod method : UnlockMethod.values()) {
            assertFalse(services.featureAvailability().isEnabled(method));
        }
        assertFalse(services.featureAvailability().isEnabled(null));
    }

    @Test
    public void productionAssemblyHidesTheVariantFaceBanner() {
        FaceBannerPolicy policy = RuntimeAssembly.createFaceBannerPolicy();

        assertFalse(policy.visible());
        assertEquals("", policy.text());
    }

    private static void assertRejected(RuntimeServices services, UnlockMethod method,
            String rawCredential) {
        CredentialAdmission admission = services.credentialPolicy().admit(method, rawCredential);

        assertFalse(admission.accepted());
        assertNull(admission.method());
        assertEquals(CREDENTIAL_UNAVAILABLE, admission.message());
    }

    private static void assertUnavailable(RuntimeServices services, UnlockMethod method,
            String rawCredential, LockerTarget target) {
        CustomerUnlockAuthorization authorization = services.unlockAuthorizer().authorize(
                method, rawCredential, null, -1L, null, null, null, null, target);

        assertFalse(authorization.authorized());
        assertNull(authorization.request());
        assertEquals(UNLOCK_UNAVAILABLE, authorization.message());
    }

    private static LockerTarget target() {
        return new LockerTarget(LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }
}
