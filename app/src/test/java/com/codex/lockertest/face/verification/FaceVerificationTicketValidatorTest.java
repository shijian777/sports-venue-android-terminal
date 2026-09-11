package com.codex.lockertest.face.verification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class FaceVerificationTicketValidatorTest {
    private static final long EXPIRY = 61_000L;
    private static final String TICKET = "00112233445566778899aabbccddeeff";

    @Test
    public void validatorReturnsEveryTypedVerdictForItsRealBoundary() {
        FaceVerificationTicketRegistry registry = new FaceVerificationTicketRegistry();
        FaceVerificationResult issued = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        registry.registerIssued(issued);

        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.VALID,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L, registry, 60_999L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.NOT_PASSED,
                FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.NOT_PASSED, "request-1"),
                true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.DISABLED,
                issued, false, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.WRONG_SOURCE,
                issued, true, FaceVerificationSource.REMOTE_SERVER,
                "request-1", "device-binding", "process-binding", 3L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.REQUEST_MISMATCH,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-other", "device-binding", "process-binding", 3L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.DEVICE_MISMATCH,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-other", "process-binding", 3L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.PROCESS_MISMATCH,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-other", 3L, registry, 1L);

        FaceVerificationResult unknown = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", "ffeeddccbbaa99887766554433221100", 3L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.TICKET_NOT_REGISTERED,
                unknown, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L, registry, 1L);

        FaceVerificationTicketRegistry revokedRegistry = new FaceVerificationTicketRegistry();
        FaceVerificationResult revoked = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        revokedRegistry.registerIssued(revoked);
        revokedRegistry.revokeAll();
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.TICKET_REVOKED,
                revoked, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L,
                revokedRegistry, 1L);

        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.POLICY_EPOCH_MISMATCH,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 4L, registry, 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.EXPIRED,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L,
                registry, EXPIRY);
    }

    @Test
    public void registryRejectsARecreatedResultEvenWhenAllStringsMatch() {
        FaceVerificationTicketRegistry registry = new FaceVerificationTicketRegistry();
        FaceVerificationResult issued = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        registry.registerIssued(issued);
        FaceVerificationResult counterfeit = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);

        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.TICKET_NOT_REGISTERED,
                counterfeit, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L, registry, 1L);
    }

    @Test
    public void revokedTombstoneSurvivesUntilOriginalExpiryThenPurges() {
        FaceVerificationTicketRegistry registry = new FaceVerificationTicketRegistry();
        FaceVerificationResult issued = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        registry.registerIssued(issued);
        registry.revokeAll();

        assertEquals(FaceVerificationTicketRegistry.EntryState.REVOKED,
                registry.stateOf(issued, EXPIRY - 1L));
        assertEquals(FaceVerificationTicketRegistry.EntryState.UNKNOWN,
                registry.stateOf(issued, EXPIRY));
        assertEquals(0, registry.size(EXPIRY));
    }

    @Test
    public void registeringANewIssuePurgesTombstonesAtTheirOriginalExpiry() {
        FaceVerificationTicketRegistry registry = new FaceVerificationTicketRegistry();
        FaceVerificationResult expired = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        registry.registerIssued(expired, 1L);
        registry.revokeAll();
        FaceVerificationResult replacement = FaceVerificationResult.passed(
                FaceVerificationSource.LOCAL_DEMO, "request-2", "credential-2", TICKET,
                EXPIRY + 60_000L, "device-binding", "process-binding", 3L);

        registry.registerIssued(replacement, EXPIRY);

        assertEquals(FaceVerificationTicketRegistry.EntryState.ACTIVE,
                registry.stateOf(replacement, EXPIRY));
        assertEquals(1, registry.size(EXPIRY));
    }

    @Test
    public void exactExpiryBoundaryWinsOverRegistryPurge() {
        FaceVerificationTicketRegistry registry = new FaceVerificationTicketRegistry();
        FaceVerificationResult issued = passed(FaceVerificationSource.LOCAL_DEMO,
                "request-1", TICKET, 3L);
        registry.registerIssued(issued);

        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.VALID,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L,
                registry, EXPIRY - 1L);
        assertVerdict(FaceVerificationTicketValidator.TicketVerdict.EXPIRED,
                issued, true, FaceVerificationSource.LOCAL_DEMO,
                "request-1", "device-binding", "process-binding", 3L,
                registry, EXPIRY);
    }

    @Test
    public void environmentTransitionsEpochOnlyOnActualChangesAndRevokesOldTickets() {
        MemoryStateStore store = new MemoryStateStore(false);
        FaceVerificationEnvironment.IssuerCapability issuerCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        FaceVerificationEnvironment environment = new FaceVerificationEnvironment(
                FaceVerificationSource.LOCAL_DEMO, true, store,
                new FixedTicketIds(TICKET), issuerCapability);

        assertEquals(0L, environment.currentPolicyEpoch());
        assertEquals(false, environment.setEnabled(false));
        assertEquals(0L, environment.currentPolicyEpoch());
        assertEquals(true, environment.setEnabled(true));
        assertEquals(1L, environment.currentPolicyEpoch());
        assertEquals(true, environment.setEnabled(true));
        assertEquals(1L, environment.currentPolicyEpoch());

        FaceVerificationResult issued = environment.issuePassed(
                issuerCapability,
                "request-1", "credential", EXPIRY,
                "device-binding", "process-binding", 1L, 1L);
        assertEquals(false, environment.setEnabled(false));
        assertEquals(2L, environment.currentPolicyEpoch());
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.DISABLED,
                environment.validateTicket(issued, "request-1",
                        "device-binding", "process-binding", 1L));
        assertEquals(true, environment.setEnabled(true));
        assertEquals(3L, environment.currentPolicyEpoch());
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.POLICY_EPOCH_MISMATCH,
                environment.validateTicket(issued, "request-1",
                        "device-binding", "process-binding", 1L));
        assertTrue(store.enabled);
    }

    @Test
    public void secureTicketGeneratorProducesDistinct128BitLowercaseHexIds() {
        String first = FaceVerificationEnvironment.secureTicketId();
        String second = FaceVerificationEnvironment.secureTicketId();

        assertTrue(first.matches("[0-9a-f]{32}"));
        assertTrue(second.matches("[0-9a-f]{32}"));
        assertNotEquals(first, second);
    }

    @Test
    public void wrongIssuerCapabilityIsRejectedBeforeTicketGeneration() {
        CountingTicketIds ticketIds = new CountingTicketIds(TICKET);
        FaceVerificationEnvironment.IssuerCapability boundCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        FaceVerificationEnvironment environment = new FaceVerificationEnvironment(
                FaceVerificationSource.LOCAL_DEMO, true,
                new MemoryStateStore(true), ticketIds, boundCapability);
        FaceVerificationEnvironment.IssuerCapability wrongCapability =
                FaceVerificationEnvironment.newIssuerCapability();

        try {
            environment.issuePassed(wrongCapability, "request-1", "credential", EXPIRY,
                    "device-binding", "process-binding", 0L, 1L);
            fail("a capability minted for another issuer must not sign a ticket");
        } catch (SecurityException expected) {
            // Expected fail-closed capability rejection.
        }

        assertEquals(0, ticketIds.calls);
    }

    private static FaceVerificationResult passed(
            FaceVerificationSource source, String requestId, String ticketId, long epoch) {
        return FaceVerificationResult.passed(source, requestId, "credential", ticketId,
                EXPIRY, "device-binding", "process-binding", epoch);
    }

    private static void assertVerdict(FaceVerificationTicketValidator.TicketVerdict expected,
            FaceVerificationResult result, boolean enabled,
            FaceVerificationSource acceptedSource, String expectedRequest,
            String expectedDevice, String expectedProcess, long currentEpoch,
            FaceVerificationTicketRegistry registry, long now) {
        assertEquals(expected, FaceVerificationTicketValidator.validate(
                result, enabled, acceptedSource, expectedRequest, expectedDevice,
                expectedProcess, currentEpoch, registry, now));
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

    private static final class CountingTicketIds
            implements FaceVerificationEnvironment.TicketIdGenerator {
        private final String ticketId;
        int calls;

        CountingTicketIds(String ticketId) {
            this.ticketId = ticketId;
        }

        @Override
        public String nextTicketId() {
            calls++;
            return ticketId;
        }
    }
}
