package com.codex.lockertest.face.verification;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Native-package test fixture for issuing real local-demo face tickets. */
public final class FaceVerificationEnvironmentFixtureTest {
    public static final class IssuedTicket {
        public final FaceVerificationEnvironment environment;
        public final FaceVerificationResult result;

        IssuedTicket(FaceVerificationEnvironment environment, FaceVerificationResult result) {
            this.environment = environment;
            this.result = result;
        }
    }

    public static IssuedTicket issue(String requestId, String credential,
            long issuedAtEpochMillis, long expiresAtEpochMillis,
            String deviceBinding, String processBinding) {
        FaceVerificationEnvironment.IssuerCapability issuerCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        FaceVerificationEnvironment environment = newEnvironment(issuerCapability);
        FaceVerificationResult result = environment.issuePassed(issuerCapability,
                requestId, credential, expiresAtEpochMillis, deviceBinding, processBinding,
                environment.currentPolicyEpoch(), issuedAtEpochMillis);
        return new IssuedTicket(environment, result);
    }

    public static FaceVerificationEnvironment newEnvironment() {
        return newEnvironment(FaceVerificationEnvironment.newIssuerCapability());
    }

    public static FaceVerificationResult reconstructed(IssuedTicket ticket) {
        FaceVerificationResult original = ticket.result;
        return FaceVerificationResult.passed(original.source(), original.requestId(),
                original.credential(), original.ticketId(), original.expiresAtEpochMillis(),
                original.deviceBinding(), original.processBinding(),
                original.verificationPolicyEpoch());
    }

    @Test
    public void issuedTicketPassesTheEnvironmentThatRegisteredIt() {
        IssuedTicket ticket = issue("request-fixture", "fixture-credential", 1L, 2L,
                "device-fixture", "process-fixture");

        assertEquals(FaceVerificationTicketValidator.TicketVerdict.VALID,
                ticket.environment.validateTicket(ticket.result, "request-fixture",
                        "device-fixture", "process-fixture", 1L));
    }

    private static FaceVerificationEnvironment newEnvironment(
            FaceVerificationEnvironment.IssuerCapability issuerCapability) {
        return new FaceVerificationEnvironment(FaceVerificationSource.LOCAL_DEMO, true,
                new EnabledStateStore(), new FixedTicketIds(), issuerCapability);
    }

    private static final class EnabledStateStore
            implements FaceVerificationEnvironment.EnabledStateStore {
        @Override public boolean load() { return true; }
        @Override public void save(boolean enabled) { }
    }

    private static final class FixedTicketIds
            implements FaceVerificationEnvironment.TicketIdGenerator {
        @Override public String nextTicketId() {
            return "00112233445566778899aabbccddeeff";
        }
    }
}
