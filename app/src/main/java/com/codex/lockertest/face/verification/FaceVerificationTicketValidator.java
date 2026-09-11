package com.codex.lockertest.face.verification;

public final class FaceVerificationTicketValidator {
    public enum TicketVerdict {
        VALID,
        NOT_PASSED,
        DISABLED,
        WRONG_SOURCE,
        REQUEST_MISMATCH,
        DEVICE_MISMATCH,
        PROCESS_MISMATCH,
        TICKET_NOT_REGISTERED,
        TICKET_REVOKED,
        POLICY_EPOCH_MISMATCH,
        EXPIRED
    }

    private FaceVerificationTicketValidator() {
    }

    static TicketVerdict validate(FaceVerificationResult result, boolean enabled,
            FaceVerificationSource acceptedSource, String expectedRequestId,
            String expectedDeviceBinding, String expectedProcessBinding,
            long currentPolicyEpoch, FaceVerificationTicketRegistry registry,
            long nowEpochMillis) {
        if (result == null || !result.isPassed()) {
            return TicketVerdict.NOT_PASSED;
        }
        if (!enabled) {
            return TicketVerdict.DISABLED;
        }
        if (result.source() != acceptedSource) {
            return TicketVerdict.WRONG_SOURCE;
        }
        if (!equals(result.requestId(), expectedRequestId)) {
            return TicketVerdict.REQUEST_MISMATCH;
        }
        if (!equals(result.deviceBinding(), expectedDeviceBinding)) {
            return TicketVerdict.DEVICE_MISMATCH;
        }
        if (!equals(result.processBinding(), expectedProcessBinding)) {
            return TicketVerdict.PROCESS_MISMATCH;
        }
        if (nowEpochMillis >= result.expiresAtEpochMillis()) {
            return TicketVerdict.EXPIRED;
        }
        if (result.verificationPolicyEpoch() != currentPolicyEpoch) {
            return TicketVerdict.POLICY_EPOCH_MISMATCH;
        }
        FaceVerificationTicketRegistry.EntryState state = registry.stateOf(result, nowEpochMillis);
        if (state == FaceVerificationTicketRegistry.EntryState.UNKNOWN) {
            return TicketVerdict.TICKET_NOT_REGISTERED;
        }
        if (state == FaceVerificationTicketRegistry.EntryState.REVOKED) {
            return TicketVerdict.TICKET_REVOKED;
        }
        return TicketVerdict.VALID;
    }

    private static boolean equals(String first, String second) {
        return first != null && first.equals(second);
    }
}
