package com.codex.lockertest.runtime;

import com.codex.lockertest.model.UnlockMethod;

public interface CredentialAdmissionPolicy {
    CredentialAdmission admit(UnlockMethod requestedMethod, String rawCredential);
}
