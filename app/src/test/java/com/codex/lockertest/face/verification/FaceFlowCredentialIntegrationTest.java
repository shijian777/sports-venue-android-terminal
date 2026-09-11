package com.codex.lockertest.face.verification;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.ui.KioskFlowModel;
import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.RuntimePolicyFixtures;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class FaceFlowCredentialIntegrationTest {
    private static final long NOW = 10_000L;
    private static final long EXPIRY = 20_000L;
    private static final String REQUEST = "request-face-1";
    private static final String CREDENTIAL = "opaque-face-credential";
    private static final String DEVICE = "device-binding";
    private static final String PROCESS = "process-binding";

    @Test
    public void genuineRegisteredTicketFlowsByIdentityAndValidatesEveryBinding() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);
        LockerTarget selected = flow.lockerSelection().selectedTarget();

        assertSame(issued, flow.pendingFaceVerification());
        assertEquals(UnlockMethod.FACE, flow.pendingCredentialMethod());
        assertEquals(CREDENTIAL, flow.pendingCredential());
        assertFalse(flow.confirmLocker(selected));
        assertFalse(flow.confirmLocker());
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(issued, flow.pendingFaceVerification());
        assertEquals(KioskFlowModel.ConfirmResult.ACCEPTED,
                flow.confirmLockerAt(selected, EXPIRY - 1L));

        assertEquals(FaceVerificationTicketValidator.TicketVerdict.VALID,
                fixture.environment.validateTicket(issued, REQUEST, DEVICE, PROCESS,
                        EXPIRY - 1L));
    }

    @Test
    public void validatorRejectsEveryIndependentBindingAndTicketBoundary() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);

        assertVerdict(fixture, issued, "request-other", DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.REQUEST_MISMATCH);
        assertVerdict(fixture, issued, REQUEST, "device-other", PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.DEVICE_MISMATCH);
        assertVerdict(fixture, issued, REQUEST, DEVICE, "process-other", NOW,
                FaceVerificationTicketValidator.TicketVerdict.PROCESS_MISMATCH);
        assertVerdict(fixture, issued, REQUEST, DEVICE, PROCESS, EXPIRY,
                FaceVerificationTicketValidator.TicketVerdict.EXPIRED);
        assertVerdict(fixture, FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.NOT_PASSED, REQUEST),
                REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.NOT_PASSED);

        Fixture otherRegistry = Fixture.enabledLocal(
                "ffeeddccbbaa99887766554433221100");
        FaceVerificationResult registeredElsewhere = otherRegistry.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        assertVerdict(fixture, registeredElsewhere, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.TICKET_NOT_REGISTERED);

        FaceVerificationResult reconstructed = FaceVerificationResult.passed(
                issued.source(), issued.requestId(), issued.credential(), issued.ticketId(),
                issued.expiresAtEpochMillis(), issued.deviceBinding(),
                issued.processBinding(), issued.verificationPolicyEpoch());
        assertVerdict(fixture, reconstructed, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.TICKET_NOT_REGISTERED);

        Fixture remote = Fixture.enabled(
                FaceVerificationSource.REMOTE_SERVER, true,
                "11223344556677889900aabbccddeeff");
        FaceVerificationResult remoteIssued = remote.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        assertVerdict(fixture, remoteIssued, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.WRONG_SOURCE);
    }

    @Test
    public void disablingAndReenablingCannotReviveAnIssuedTicket() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);

        assertFalse(fixture.environment.setEnabled(false));
        assertVerdict(fixture, issued, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.DISABLED);
        assertTrue(fixture.environment.setEnabled(true));
        assertVerdict(fixture, issued, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.POLICY_EPOCH_MISMATCH);

        Fixture productionShaped = Fixture.enabled(
                FaceVerificationSource.REMOTE_SERVER, false,
                "22334455667788990011aabbccddeeff");
        assertFalse(productionShaped.environment.isEnabled());
        assertFalse(productionShaped.environment.setEnabled(true));
        assertVerdict(productionShaped, issued, REQUEST, DEVICE, PROCESS, NOW,
                FaceVerificationTicketValidator.TicketVerdict.DISABLED);
    }

    @Test
    public void exactExpiryClearsFaceCredentialTopologyAndSelectionBackToFace() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);
        LockerTarget selected = flow.lockerSelection().selectedTarget();

        assertEquals(KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED,
                flow.confirmLockerAt(selected, EXPIRY));
        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
        assertNull(flow.pendingFaceVerification());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.pendingTarget());
        assertNull(flow.lockerSelection().selectedTarget());
        assertNull(flow.lockerSelection().layoutSnapshot());
        assertNull(flow.lockerSelection().activeArea());
        assertNull(flow.lockerSelection().activePage());
    }

    @Test
    public void faceConfirmationPreflightIsPureAtExpiryMinusOneAndExactExpiry() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);
        LockerTarget selected = flow.lockerSelection().selectedTarget();

        assertEquals(KioskFlowModel.ConfirmResult.ACCEPTED,
                checkLockerConfirmationAt(flow, selected, EXPIRY - 1L));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(issued, flow.pendingFaceVerification());
        assertSame(selected, flow.lockerSelection().selectedTarget());
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertNull(flow.pendingTarget());

        assertEquals(KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED,
                checkLockerConfirmationAt(flow, selected, EXPIRY));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(issued, flow.pendingFaceVerification());
        assertSame(selected, flow.lockerSelection().selectedTarget());
        assertTrue(flow.lockerSelection().isInteractionEnabled());
        assertNull(flow.pendingTarget());
    }

    @Test
    public void acceptanceRejectsInvalidOrExactExpiryTimeWithoutStoringATicket() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel negativeClock = newFlow();
        KioskFlowModel exactExpiry = newFlow();
        assertTrue(negativeClock.beginFaceRecognition());
        assertTrue(exactExpiry.beginFaceRecognition());

        assertFalse(negativeClock.acceptFaceVerification(issued, -1L));
        assertFalse(exactExpiry.acceptFaceVerification(issued, EXPIRY));

        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, negativeClock.screen());
        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, exactExpiry.screen());
        assertNull(negativeClock.pendingFaceVerification());
        assertNull(exactExpiry.pendingFaceVerification());
    }

    @Test
    public void invalidLockerDoesNotConsumeOrClearAStillValidFaceTicket() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);
        LockerTarget selected = flow.lockerSelection().selectedTarget();
        LockerTarget offline = new LockerTarget(
                LockerZone.B, LockerZone.B.boardAddress(), 1,
                selected.feedbackPolarity());

        assertEquals(KioskFlowModel.ConfirmResult.INVALID_SELECTION,
                flow.confirmLockerAt(offline, EXPIRY - 1L));
        assertEquals(KioskFlowModel.Screen.LOCKER_SELECTION, flow.screen());
        assertSame(issued, flow.pendingFaceVerification());
        assertSame(selected, flow.lockerSelection().selectedTarget());
    }

    @Test
    public void cancellingFaceAuthenticatedSelectionClearsTheFrozenTicket() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);

        assertTrue(flow.cancelLockerSelection());

        assertEquals(KioskFlowModel.Screen.HOME, flow.screen());
        assertNull(flow.pendingFaceVerification());
        assertNull(flow.pendingCredentialMethod());
        assertNull(flow.pendingCredential());
        assertNull(flow.pendingTarget());
        assertNull(flow.lockerSelection().selectedTarget());
    }

    @Test
    public void unlockRetryKeepsTheSameTicketButCannotExtendItsExpiry() {
        Fixture fixture = Fixture.enabledLocal("00112233445566778899aabbccddeeff");
        FaceVerificationResult issued = fixture.issue(
                REQUEST, CREDENTIAL, EXPIRY, DEVICE, PROCESS, NOW);
        KioskFlowModel flow = acceptedReadyFlow(issued);
        LockerTarget selected = flow.lockerSelection().selectedTarget();

        assertEquals(KioskFlowModel.ConfirmResult.ACCEPTED,
                flow.confirmLockerAt(selected, EXPIRY - 1L));
        assertTrue(flow.finishUnlockFailure());
        assertTrue(flow.retry());
        assertSame(issued, flow.pendingFaceVerification());
        assertEquals(KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED,
                flow.confirmLockerAt(selected, EXPIRY));
        assertNull(flow.pendingFaceVerification());
        assertEquals(KioskFlowModel.Screen.FACE_RECOGNITION, flow.screen());
    }

    private static KioskFlowModel acceptedReadyFlow(FaceVerificationResult result) {
        KioskFlowModel flow = newFlow();
        assertTrue(flow.beginFaceRecognition());
        assertTrue(flow.acceptFaceVerification(result, NOW));
        assertTrue(flow.applyDiscoverySnapshot(Collections.singletonList(LockerZone.A)));
        assertTrue(flow.lockerSelection().selectLocalLock(1));
        return flow;
    }

    private static KioskFlowModel.ConfirmResult checkLockerConfirmationAt(
            KioskFlowModel flow, LockerTarget target, long nowEpochMillis) {
        try {
            Method method = KioskFlowModel.class.getMethod(
                    "checkLockerConfirmationAt", LockerTarget.class, long.class);
            return (KioskFlowModel.ConfirmResult) method.invoke(
                    flow, target, nowEpochMillis);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("missing pure confirmation preflight", exception);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("confirmation preflight is not public", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("confirmation preflight threw", exception.getCause());
        }
    }

    private static KioskFlowModel newFlow() {
        return new KioskFlowModel(
                RuntimePolicyFixtures.demoCredentialPolicy(),
                RuntimePolicyFixtures.legacyLayoutPolicy(),
                TerminalReadiness.localDemoReady());
    }

    private static void assertVerdict(Fixture fixture, FaceVerificationResult result,
            String expectedRequest, String expectedDevice, String expectedProcess, long now,
            FaceVerificationTicketValidator.TicketVerdict expected) {
        assertEquals(expected, fixture.environment.validateTicket(result, expectedRequest,
                expectedDevice, expectedProcess, now));
    }

    private static final class Fixture {
        final MemoryStateStore stateStore;
        final FaceVerificationEnvironment.IssuerCapability issuerCapability;
        final FaceVerificationEnvironment environment;

        private Fixture(FaceVerificationSource source, boolean enablingAllowed,
                String ticketId) {
            stateStore = new MemoryStateStore(true);
            issuerCapability = FaceVerificationEnvironment.newIssuerCapability();
            environment = new FaceVerificationEnvironment(source, enablingAllowed,
                    stateStore, new FixedTicketIds(ticketId), issuerCapability);
        }

        static Fixture enabledLocal(String ticketId) {
            return enabled(FaceVerificationSource.LOCAL_DEMO, true, ticketId);
        }

        static Fixture enabled(FaceVerificationSource source, boolean enablingAllowed,
                String ticketId) {
            return new Fixture(source, enablingAllowed, ticketId);
        }

        FaceVerificationResult issue(String requestId, String credential, long expiresAt,
                String deviceBinding, String processBinding, long issuedAt) {
            return environment.issuePassed(issuerCapability, requestId, credential,
                    expiresAt, deviceBinding, processBinding,
                    environment.currentPolicyEpoch(), issuedAt);
        }
    }

    private static final class MemoryStateStore
            implements FaceVerificationEnvironment.EnabledStateStore {
        private boolean enabled;

        MemoryStateStore(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean load() {
            return enabled;
        }

        @Override
        public void save(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static final class FixedTicketIds
            implements FaceVerificationEnvironment.TicketIdGenerator {
        private final String ticketId;

        FixedTicketIds(String ticketId) {
            this.ticketId = ticketId;
        }

        @Override
        public String nextTicketId() {
            return ticketId;
        }
    }
}
