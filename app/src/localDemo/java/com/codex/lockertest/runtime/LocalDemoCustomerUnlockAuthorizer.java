package com.codex.lockertest.runtime;

import com.codex.lockertest.face.verification.FaceVerificationEnvironment;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationTicketValidator;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.concurrent.atomic.AtomicLong;

/** Grants local-demo serial authority only after local credential admission or face ticket validation. */
public final class LocalDemoCustomerUnlockAuthorizer implements CustomerUnlockAuthorizer {
    private static final String UNREGISTERED = "凭证未登记";

    private final LocalDemoCredentialAdmissionPolicy credentialPolicy =
            new LocalDemoCredentialAdmissionPolicy();
    private final AtomicLong nextOperationId = new AtomicLong();

    @Override
    public CustomerUnlockAuthorization authorize(UnlockMethod method, String rawCredential,
            FaceVerificationResult faceResult, long nowEpochMillis, String expectedRequestId,
            String expectedDeviceBinding, String expectedProcessBinding,
            FaceVerificationEnvironment environment, LockerTarget target) {
        if (target == null || !isAuthorized(method, rawCredential, faceResult, nowEpochMillis,
                expectedRequestId, expectedDeviceBinding, expectedProcessBinding, environment)) {
            return CustomerUnlockAuthorization.unavailable(UNREGISTERED);
        }
        long operationId = nextOperationId.incrementAndGet();
        AuthorizedUnlockRequest request = new AuthorizedUnlockRequest(
                operationId,
                target,
                LockerProtocol.unlockCommand(target),
                LockerProtocol.successFrame(target),
                LockerProtocol.failureFrame(target));
        return CustomerUnlockAuthorization.authorized(request);
    }

    private boolean isAuthorized(UnlockMethod method, String rawCredential,
            FaceVerificationResult faceResult, long nowEpochMillis, String expectedRequestId,
            String expectedDeviceBinding, String expectedProcessBinding,
            FaceVerificationEnvironment environment) {
        if (method != UnlockMethod.FACE) {
            return credentialPolicy.admit(method, rawCredential).accepted();
        }
        if (rawCredential == null || faceResult == null || environment == null
                || nowEpochMillis < 0L || !rawCredential.equals(faceResult.credential())) {
            return false;
        }
        try {
            return environment.validateTicket(faceResult, expectedRequestId, expectedDeviceBinding,
                    expectedProcessBinding, nowEpochMillis)
                    == FaceVerificationTicketValidator.TicketVerdict.VALID;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
