package com.codex.lockertest.runtime;

import com.codex.lockertest.face.verification.FaceVerificationEnvironment;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.UnlockMethod;

/** Issues no serial request until the production server unlock protocol is configured. */
public final class FailClosedCustomerUnlockAuthorizer implements CustomerUnlockAuthorizer {
    @Override
    public CustomerUnlockAuthorization authorize(
            UnlockMethod method,
            String rawCredential,
            FaceVerificationResult faceResult,
            long nowEpochMillis,
            String expectedRequestId,
            String expectedDeviceBinding,
            String expectedProcessBinding,
            FaceVerificationEnvironment environment,
            LockerTarget target) {
        return CustomerUnlockAuthorization.unavailable("服务器开柜协议未配置");
    }
}
