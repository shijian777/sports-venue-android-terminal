package com.codex.lockertest.runtime;

import com.codex.lockertest.face.verification.FaceVerificationEnvironment;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.UnlockMethod;

public interface CustomerUnlockAuthorizer {
    CustomerUnlockAuthorization authorize(
            UnlockMethod method,
            String rawCredential,
            FaceVerificationResult faceResult,
            long nowEpochMillis,
            String expectedRequestId,
            String expectedDeviceBinding,
            String expectedProcessBinding,
            FaceVerificationEnvironment environment,
            LockerTarget target);
}
