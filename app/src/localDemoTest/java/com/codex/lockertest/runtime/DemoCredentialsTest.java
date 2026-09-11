package com.codex.lockertest.runtime;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DemoCredentialsTest {
    @Test
    public void phoneAcceptsOnlyTheExactDemoNumber() {
        assertTrue(DemoCredentials.isValidPhone("13800138000"));
        assertFalse(DemoCredentials.isValidPhone(null));
        assertFalse(DemoCredentials.isValidPhone(""));
        assertFalse(DemoCredentials.isValidPhone(" 13800138000"));
        assertFalse(DemoCredentials.isValidPhone("13800138000 "));
        assertFalse(DemoCredentials.isValidPhone("1380013800"));
        assertFalse(DemoCredentials.isValidPhone("138001380000"));
        assertFalse(DemoCredentials.isValidPhone("13800138001"));
    }

    @Test
    public void passwordAcceptsOnlyTheExactSixDigits() {
        assertTrue(DemoCredentials.isValidPassword("123456"));
        assertFalse(DemoCredentials.isValidPassword(null));
        assertFalse(DemoCredentials.isValidPassword(""));
        assertFalse(DemoCredentials.isValidPassword(" 123456"));
        assertFalse(DemoCredentials.isValidPassword("123456 "));
        assertFalse(DemoCredentials.isValidPassword("12345"));
        assertFalse(DemoCredentials.isValidPassword("1234567"));
        assertFalse(DemoCredentials.isValidPassword("123457"));
    }

    @Test
    public void passiveScannerAcceptsOnlyTheExactConfiguredIdCardOrQrPayload() {
        String qr = "111993413628001787216027-00144049324404404044044~712~1~3~"
                + "30303030303137373331";
        String priorV11Qr = "3413628001787216027-00144049324404404044044~712~1~3~"
                + "303030303031373773331";

        assertTrue(DemoCredentials.isValidIdCard("0014872138"));
        assertTrue(DemoCredentials.isValidQrCode(qr));
        assertTrue(DemoCredentials.isValidScannedCredential("0014872138"));
        assertTrue(DemoCredentials.isValidScannedCredential(qr));
        assertFalse(DemoCredentials.isValidIdCard(null));
        assertFalse(DemoCredentials.isValidScannedCredential(""));
        assertFalse(DemoCredentials.isValidScannedCredential("001472138"));
        assertFalse(DemoCredentials.isValidScannedCredential(priorV11Qr));
        assertFalse(DemoCredentials.isValidScannedCredential(qr + "0"));
        assertFalse(DemoCredentials.isValidScannedCredential(qr.substring(1)));
        assertFalse(DemoCredentials.isValidScannedCredential(qr + "\n"));
    }

    @Test
    public void administratorPinAcceptsOnlyTheExactSixDigits() {
        assertTrue(DemoCredentials.isValidAdminPin("888888"));
        assertFalse(DemoCredentials.isValidAdminPin(null));
        assertFalse(DemoCredentials.isValidAdminPin(""));
        assertFalse(DemoCredentials.isValidAdminPin(" 888888"));
        assertFalse(DemoCredentials.isValidAdminPin("888888 "));
        assertFalse(DemoCredentials.isValidAdminPin("88888"));
        assertFalse(DemoCredentials.isValidAdminPin("8888888"));
        assertFalse(DemoCredentials.isValidAdminPin("888889"));
    }

    @Test
    public void defaultFeaturesExposePhonePasswordAndIdCard() {
        FeatureAvailability availability = DemoFeatureFlags.defaults();

        assertFalse(availability.isEnabled(UnlockMethod.FACE));
        assertFalse(availability.isEnabled(UnlockMethod.PALM));
        assertTrue(availability.isEnabled(UnlockMethod.PHONE));
        assertTrue(availability.isEnabled(UnlockMethod.PASSWORD));
        assertTrue(availability.isEnabled(UnlockMethod.ID_CARD));
        assertFalse(availability.isEnabled(UnlockMethod.QR));
        assertFalse(availability.isEnabled(null));
    }
}
