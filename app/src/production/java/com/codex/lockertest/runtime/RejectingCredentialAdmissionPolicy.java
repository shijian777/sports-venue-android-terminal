package com.codex.lockertest.runtime;

import com.codex.lockertest.model.UnlockMethod;

/** Rejects all customer credentials until server-side authentication is configured. */
public final class RejectingCredentialAdmissionPolicy implements CredentialAdmissionPolicy {
    @Override
    public CredentialAdmission admit(UnlockMethod method, String value) {
        return CredentialAdmission.rejected("服务器认证尚未配置");
    }
}
