package com.codex.lockertest.runtime;

import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationStatus;
import com.codex.lockertest.face.verification.FaceVerificationEnvironmentFixtureTest;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class LocalDemoCustomerUnlockAuthorizerTest {
    @Test
    public void registeredCredentialsCreateExactTargetBoundFramesWithMonotonicOperationIds() {
        LocalDemoCustomerUnlockAuthorizer authorizer = new LocalDemoCustomerUnlockAuthorizer();
        LockerTarget a1 = target(LockerZone.A, 1);
        LockerTarget c2 = target(LockerZone.C, 2);

        CustomerUnlockAuthorization first = authorizer.authorize(
                UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, null, 1L,
                "request-1", "device-1", "process-1", null, a1);
        CustomerUnlockAuthorization second = authorizer.authorize(
                UnlockMethod.ID_CARD, DemoCredentials.QR_CODE, null, 1L,
                "request-2", "device-2", "process-2", null, c2);

        assertTrue(first.authorized());
        assertEquals(1L, first.request().operationId());
        assertArrayEquals(bytes(0x8a, 0x01, 0x01, 0x11, 0x9b),
                first.request().unlockCommand());
        assertArrayEquals(bytes(0x8a, 0x01, 0x01, 0x00, 0x8a),
                first.request().expectedSuccessFrame());
        assertArrayEquals(bytes(0x8a, 0x01, 0x01, 0x11, 0x9b),
                first.request().expectedFailureFrame());
        assertTrue(second.authorized());
        assertEquals(2L, second.request().operationId());
        assertArrayEquals(bytes(0x8a, 0x03, 0x02, 0x11, 0x9a),
                second.request().unlockCommand());
        assertArrayEquals(bytes(0x8a, 0x03, 0x02, 0x00, 0x8b),
                second.request().expectedSuccessFrame());
        assertArrayEquals(bytes(0x8a, 0x03, 0x02, 0x11, 0x9a),
                second.request().expectedFailureFrame());
    }

    @Test
    public void unregisteredAndInvalidFaceCredentialsCannotAuthorizeUnlock() {
        LocalDemoCustomerUnlockAuthorizer authorizer = new LocalDemoCustomerUnlockAuthorizer();
        LockerTarget target = target(LockerZone.A, 1);

        CustomerUnlockAuthorization unknown = authorizer.authorize(
                UnlockMethod.ID_CARD, "unknown", null, 1L,
                "request-1", "device-1", "process-1", null, target);
        CustomerUnlockAuthorization failedFace = authorizer.authorize(
                UnlockMethod.FACE, "face-credential", FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.NOT_PASSED, "request-1"), 1L,
                "request-1", "device-1", "process-1", null, target);

        assertFalse(unknown.authorized());
        assertEquals("凭证未登记", unknown.message());
        assertFalse(failedFace.authorized());
    }

    @Test
    public void missingRequestContextCannotIssueAuthorityOrConsumeAnOperationId() {
        LocalDemoCustomerUnlockAuthorizer authorizer = new LocalDemoCustomerUnlockAuthorizer();
        LockerTarget target = target(LockerZone.A, 1);
        FaceVerificationEnvironmentFixtureTest.IssuedTicket ticket =
                FaceVerificationEnvironmentFixtureTest.issue(
                        "request-face", "face-credential", 1_000L, 2_000L,
                        "device-face", "process-face");

        CustomerUnlockAuthorization missingTarget = authorizer.authorize(
                UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, null, 1_000L,
                "request-card", "device-face", "process-face", null, null);
        CustomerUnlockAuthorization missingRequestId = authorizer.authorize(
                UnlockMethod.FACE, ticket.result.credential(), ticket.result, 1_000L,
                null, "device-face", "process-face", ticket.environment, target);
        CustomerUnlockAuthorization issued = authorizer.authorize(
                UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, null, 1_000L,
                "request-card", "device-face", "process-face", null, target);

        assertFalse(missingTarget.authorized());
        assertNull(missingTarget.request());
        assertFalse(missingRequestId.authorized());
        assertNull(missingRequestId.request());
        assertTrue(issued.authorized());
        assertEquals(1L, issued.request().operationId());
    }

    @Test
    public void issuedFaceTicketCreatesAnExactTargetBoundUnlockRequest() {
        FaceVerificationEnvironmentFixtureTest.IssuedTicket ticket =
                FaceVerificationEnvironmentFixtureTest.issue(
                        "request-face", "face-credential", 1_000L, 2_000L,
                        "device-face", "process-face");
        CustomerUnlockAuthorization authorization = new LocalDemoCustomerUnlockAuthorizer()
                .authorize(UnlockMethod.FACE, ticket.result.credential(), ticket.result, 1_000L,
                        "request-face", "device-face", "process-face", ticket.environment,
                        target(LockerZone.B, 1));

        assertTrue(authorization.authorized());
        assertEquals(1L, authorization.request().operationId());
        assertArrayEquals(bytes(0x8a, 0x02, 0x01, 0x11, 0x98),
                authorization.request().unlockCommand());
        assertArrayEquals(bytes(0x8a, 0x02, 0x01, 0x00, 0x89),
                authorization.request().expectedSuccessFrame());
        assertArrayEquals(bytes(0x8a, 0x02, 0x01, 0x11, 0x98),
                authorization.request().expectedFailureFrame());
    }

    @Test
    public void faceAuthorizationRejectsTicketsThatTheExistingValidatorDoesNotAccept() {
        FaceVerificationEnvironmentFixtureTest.IssuedTicket ticket =
                FaceVerificationEnvironmentFixtureTest.issue(
                        "request-face", "face-credential", 1_000L, 2_000L,
                        "device-face", "process-face");
        LocalDemoCustomerUnlockAuthorizer authorizer = new LocalDemoCustomerUnlockAuthorizer();
        LockerTarget target = target(LockerZone.B, 1);

        assertRejectedFace(authorizer, ticket.result.credential(),
                FaceVerificationEnvironmentFixtureTest.reconstructed(ticket), 1_000L,
                "request-face", "device-face", "process-face", ticket.environment, target);
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 1_000L,
                "request-face", "device-face", "process-face",
                FaceVerificationEnvironmentFixtureTest.newEnvironment(), target);
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 2_000L,
                "request-face", "device-face", "process-face", ticket.environment, target);
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 1_000L,
                "request-face", "device-other", "process-face", ticket.environment, target);
        assertRejectedFace(authorizer, "wrong-face-credential", ticket.result, 1_000L,
                "request-face", "device-face", "process-face", ticket.environment, target);
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 1_000L,
                "request-other", "device-face", "process-face", ticket.environment, target);
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 1_000L,
                "request-face", "device-face", "process-other", ticket.environment, target);
        assertFalse(ticket.environment.setEnabled(false));
        assertRejectedFace(authorizer, ticket.result.credential(), ticket.result, 1_000L,
                "request-face", "device-face", "process-face", ticket.environment, target);
    }

    private static void assertRejectedFace(LocalDemoCustomerUnlockAuthorizer authorizer,
            String rawCredential, FaceVerificationResult faceResult, long nowEpochMillis,
            String requestId, String deviceBinding, String processBinding,
            com.codex.lockertest.face.verification.FaceVerificationEnvironment environment,
            LockerTarget target) {
        CustomerUnlockAuthorization authorization = authorizer.authorize(UnlockMethod.FACE,
                rawCredential, faceResult, nowEpochMillis, requestId, deviceBinding,
                processBinding, environment, target);

        assertFalse(authorization.authorized());
        assertNull(authorization.request());
    }

    private static LockerTarget target(LockerZone zone, int lock) {
        return new LockerTarget(zone, zone.boardAddress(), lock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
