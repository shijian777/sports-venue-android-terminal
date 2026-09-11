package com.codex.lockertest.runtime;

import com.codex.lockertest.model.UnlockMethod;

/** Admits only the local-demo fixtures and never retains their raw values. */
public final class LocalDemoCredentialAdmissionPolicy implements CredentialAdmissionPolicy {
    private static final String UNREGISTERED = "凭证未登记";

    @Override
    public CredentialAdmission admit(UnlockMethod requestedMethod, String rawCredential) {
        if (requestedMethod == UnlockMethod.PHONE
                && DemoCredentials.isValidPhone(rawCredential)) {
            return CredentialAdmission.accepted(UnlockMethod.PHONE);
        }
        if (requestedMethod == UnlockMethod.PASSWORD
                && DemoCredentials.isValidPassword(rawCredential)) {
            return CredentialAdmission.accepted(UnlockMethod.PASSWORD);
        }
        if (requestedMethod == UnlockMethod.ID_CARD) {
            if (DemoCredentials.isValidIdCard(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.ID_CARD);
            }
            if (DemoCredentials.isValidQrCode(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.QR);
            }
        }
        if (requestedMethod == UnlockMethod.QR && DemoCredentials.isValidQrCode(rawCredential)) {
            return CredentialAdmission.accepted(UnlockMethod.QR);
        }
        return CredentialAdmission.rejected(UNREGISTERED);
    }
}
