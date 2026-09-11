package com.codex.lockertest.runtime;

import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalDemoPolicyTest {
    @Test
    public void scannerCredentialKeepsTheMethodOfItsActualCredential() {
        LocalDemoCredentialAdmissionPolicy policy = new LocalDemoCredentialAdmissionPolicy();

        assertEquals(UnlockMethod.ID_CARD,
                policy.admit(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD).method());
        assertEquals(UnlockMethod.QR,
                policy.admit(UnlockMethod.ID_CARD, DemoCredentials.QR_CODE).method());
    }

    @Test
    public void admissionAcceptsOnlyTheRegisteredValueForItsEntryPoint() {
        LocalDemoCredentialAdmissionPolicy policy = new LocalDemoCredentialAdmissionPolicy();

        assertTrue(policy.admit(UnlockMethod.PHONE, DemoCredentials.PHONE).accepted());
        assertTrue(policy.admit(UnlockMethod.PASSWORD, DemoCredentials.PASSWORD).accepted());
        assertTrue(policy.admit(UnlockMethod.QR, DemoCredentials.QR_CODE).accepted());
        CredentialAdmission rejected = policy.admit(UnlockMethod.ID_CARD, "not-registered");
        assertFalse(rejected.accepted());
        assertEquals("凭证未登记", rejected.message());
        assertFalse(policy.admit(UnlockMethod.FACE, DemoCredentials.ID_CARD).accepted());
        assertFalse(policy.admit(UnlockMethod.PHONE, DemoCredentials.PASSWORD).accepted());
    }

    @Test
    public void administratorPolicyOnlyAcceptsTheSixCharacterDemoPin() {
        LocalDemoAdminCredentialPolicy policy = new LocalDemoAdminCredentialPolicy();

        assertEquals(6, policy.requiredLength());
        assertTrue(policy.matches(new char[] {'8', '8', '8', '8', '8', '8'}));
        assertFalse(policy.matches(new char[] {'8', '8', '8', '8', '8', '9'}));
        assertFalse(policy.matches(new char[] {'8', '8', '8', '8', '8'}));
        assertFalse(policy.matches(null));
    }

    @Test
    public void layoutPolicyUsesTheEmptyCompatibilityLayoutInitiallyAndDiscoveryLayoutAfterward() {
        LocalDemoInitialLayoutPolicy policy = new LocalDemoInitialLayoutPolicy();

        assertEquals(0L, policy.initialLayout().version());
        assertFalse(policy.initialLayout().areas().get(0).pages().get(0).slotAt(1, 1).enabled());
        assertEquals(5L, policy.layoutForDiscovery(Arrays.asList(LockerZone.A, LockerZone.C))
                .version());
        assertTrue(policy.layoutForDiscovery(Collections.singletonList(LockerZone.A))
                .areas().get(0).pages().get(0).slotAt(1, 1).enabled());
    }
}
