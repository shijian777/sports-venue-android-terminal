package com.codex.lockertest.integration;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.serial.SerialWriteAttribution;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.FACE_PRE_SELECTION;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.LOCKER_CONFIRMED;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.LOCKER_DISCOVERY;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.RETURN_DOOR_STATUS;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.RETURN_UNLOCK;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase.TERMINAL;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.ALLOWED;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.ATTRIBUTION_MISMATCH;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.DUPLICATE;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.INACTIVE_OPERATION;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.INVALID_REQUEST;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.PAYLOAD_NOT_ALLOWED;
import static com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision.PHASE_MISMATCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CustomerSerialWritePolicyTest {
    @Test
    public void invalidBeginRequestsFailWithoutConsumingTheOperationId() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget a1 = a1(FeedbackPolarity.SHORT_WHEN_LOCKED);

        assertFalse(policy.beginOperation(0L, LOCKER_DISCOVERY, null));
        assertFalse(policy.beginOperation(-1L, LOCKER_DISCOVERY, null));
        assertFalse(policy.beginOperation(7L, null, null));
        assertFalse(policy.beginOperation(7L, LOCKER_CONFIRMED, null));
        assertFalse(policy.beginOperation(7L, LOCKER_DISCOVERY, a1));
        assertFalse(policy.beginOperation(7L, FACE_PRE_SELECTION, a1));
        assertFalse(policy.beginOperation(7L, TERMINAL, a1));

        assertTrue(policy.beginOperation(7L, LOCKER_DISCOVERY, null));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 7L, SerialWriteAttribution.discovery(1), queryOne()));
    }

    @Test
    public void invalidAuthorizeInputsFailClosedBeforeOperationLookup() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        SerialWriteAttribution boardOne = SerialWriteAttribution.discovery(1);

        assertEquals(INVALID_REQUEST,
                policy.authorize(null, 1L, boardOne, queryOne()));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 0L, boardOne, queryOne()));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, -1L, boardOne, queryOne()));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 1L, null, queryOne()));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, null));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, new byte[0]));
        assertEquals(INACTIVE_OPERATION,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, queryOne()));

        assertTrue(policy.beginOperation(1L, LOCKER_DISCOVERY, null));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, null));
        assertEquals(INVALID_REQUEST,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, new byte[0]));
        assertEquals(ALLOWED,
                policy.authorize(LOCKER_DISCOVERY, 1L, boardOne, queryOne()));
    }

    @Test
    public void discoveryAllowsOnlyTheThreeExactQueriesOnceEachInAnyOrder() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        assertTrue(policy.beginOperation(10L, LOCKER_DISCOVERY, null));

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(3), queryThree()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(1), queryOne()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(2), queryTwo()));

        assertEquals(DUPLICATE, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(3), queryThree()));
        assertEquals(DUPLICATE, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(1), queryOne()));
        assertEquals(DUPLICATE, policy.authorize(
                LOCKER_DISCOVERY, 10L, SerialWriteAttribution.discovery(2), queryTwo()));
    }

    @Test
    public void discoveryRejectsMalformedResponsesFourthBoardAndOtherFramesWithoutUsingQuota() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        SerialWriteAttribution boardOne = SerialWriteAttribution.discovery(1);
        assertTrue(policy.beginOperation(11L, LOCKER_DISCOVERY, null));

        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x80, 0x01, 0x01, 0x33, 0xB2)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x80, 0x01, 0x01, 0x33)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne,
                bytes(0x80, 0x01, 0x01, 0x33, 0xB3, 0x00)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x80, 0x01, 0x01, 0x00, 0x80)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x80, 0x04, 0x01, 0x33, 0xB6)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x80, 0x01, 0x02, 0x33, 0xB0)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 11L, boardOne, bytes(0x8A, 0x01, 0x01, 0x11, 0x9B)));

        assertEquals(ALLOWED,
                policy.authorize(LOCKER_DISCOVERY, 11L, boardOne, queryOne()));
    }

    @Test
    public void discoveryRejectsEveryProtocolShaped8AFrame() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        assertTrue(policy.beginOperation(12L, LOCKER_DISCOVERY, null));

        for (int board = 1; board <= 3; board++) {
            SerialWriteAttribution attribution = SerialWriteAttribution.discovery(board);
            for (int lock = 1; lock <= 12; lock++) {
                for (int feedback : new int[]{0x00, 0x11}) {
                    int checksum = 0x8A ^ board ^ lock ^ feedback;
                    assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                            LOCKER_DISCOVERY,
                            12L,
                            attribution,
                            bytes(0x8A, board, lock, feedback, checksum)));
                }
            }
        }

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 12L, SerialWriteAttribution.discovery(1), queryOne()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 12L, SerialWriteAttribution.discovery(2), queryTwo()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 12L, SerialWriteAttribution.discovery(3), queryThree()));
    }

    @Test
    public void discoveryBindsEachExactQueryToItsStructuredOriginAndBoard() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        assertTrue(policy.beginOperation(13L, LOCKER_DISCOVERY, null));

        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_DISCOVERY,
                13L,
                SerialWriteAttribution.discovery(2),
                queryOne()));
        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_DISCOVERY,
                13L,
                SerialWriteAttribution.unlock(a1(FeedbackPolarity.SHORT_WHEN_LOCKED)),
                queryOne()));

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY,
                13L,
                SerialWriteAttribution.discovery(1),
                queryOne()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY,
                13L,
                SerialWriteAttribution.discovery(2),
                queryTwo()));
    }

    @Test
    public void successfulDiscoveryCopiesCallerPayloadBeforeRecordingItsQuota() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        byte[] callerPayload = queryOne();
        assertTrue(policy.beginOperation(14L, LOCKER_DISCOVERY, null));

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY,
                14L,
                SerialWriteAttribution.discovery(1),
                callerPayload));
        callerPayload[1] = 0x02;
        callerPayload[4] = (byte) 0xB0;

        assertEquals(DUPLICATE, policy.authorize(
                LOCKER_DISCOVERY,
                14L,
                SerialWriteAttribution.discovery(1),
                queryOne()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY,
                14L,
                SerialWriteAttribution.discovery(2),
                queryTwo()));
    }

    @Test
    public void confirmedUnlockUsesTargetFieldsInsteadOfObjectIdentityAndAllowsOnlyOnce() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget confirmed = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        LockerTarget equivalentCopy = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        assertFalse(confirmed == equivalentCopy);
        assertTrue(policy.beginOperation(20L, LOCKER_CONFIRMED, confirmed));

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                20L,
                SerialWriteAttribution.unlock(equivalentCopy),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
        assertEquals(DUPLICATE, policy.authorize(
                LOCKER_CONFIRMED,
                20L,
                SerialWriteAttribution.unlock(b2(FeedbackPolarity.SHORT_WHEN_OPEN)),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
    }

    @Test
    public void confirmedUnlockRejectsWrongTargetPolarityOriginAndPayloadWithoutUsingQuota() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget expected = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        SerialWriteAttribution expectedAttribution = SerialWriteAttribution.unlock(
                b2(FeedbackPolarity.SHORT_WHEN_OPEN));
        assertTrue(policy.beginOperation(21L, LOCKER_CONFIRMED, expected));

        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                SerialWriteAttribution.unlock(b2(FeedbackPolarity.SHORT_WHEN_LOCKED)),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                SerialWriteAttribution.unlock(new LockerTarget(
                        LockerZone.B, 2, 3, FeedbackPolarity.SHORT_WHEN_OPEN)),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                SerialWriteAttribution.unlock(new LockerTarget(
                        LockerZone.C, 3, 2, FeedbackPolarity.SHORT_WHEN_OPEN)),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                SerialWriteAttribution.discovery(2),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));

        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                bytes(0x8A, 0x02, 0x03, 0x11, 0x9A)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                bytes(0x8A, 0x02, 0x02, 0x00, 0x8A)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                queryTwo()));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9A)));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                bytes(0x8A, 0x02, 0x02, 0x11)));

        assertEquals(ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                21L,
                expectedAttribution,
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B)));
    }

    @Test
    public void activePhaseMustMatchAndPreSelectionOrTerminalNeverAuthorize() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget a1 = a1(FeedbackPolarity.SHORT_WHEN_LOCKED);

        assertTrue(policy.beginOperation(30L, LOCKER_DISCOVERY, null));
        assertEquals(PHASE_MISMATCH, policy.authorize(
                LOCKER_CONFIRMED,
                30L,
                SerialWriteAttribution.unlock(a1),
                bytes(0x8A, 0x01, 0x01, 0x11, 0x9B)));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY,
                30L,
                SerialWriteAttribution.discovery(1),
                queryOne()));

        assertTrue(policy.beginOperation(31L, LOCKER_CONFIRMED, a1));
        assertEquals(PHASE_MISMATCH, policy.authorize(
                LOCKER_DISCOVERY,
                31L,
                SerialWriteAttribution.discovery(1),
                queryOne()));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_CONFIRMED,
                31L,
                SerialWriteAttribution.unlock(a1(FeedbackPolarity.SHORT_WHEN_LOCKED)),
                bytes(0x8A, 0x01, 0x01, 0x11, 0x9B)));

        assertTrue(policy.beginOperation(32L, FACE_PRE_SELECTION, null));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                FACE_PRE_SELECTION,
                32L,
                SerialWriteAttribution.discovery(1),
                queryOne()));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                FACE_PRE_SELECTION,
                32L,
                SerialWriteAttribution.unlock(a1),
                bytes(0x8A, 0x01, 0x01, 0x11, 0x9B)));

        assertTrue(policy.beginOperation(33L, TERMINAL, null));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                TERMINAL,
                33L,
                SerialWriteAttribution.discovery(1),
                queryOne()));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                TERMINAL,
                33L,
                SerialWriteAttribution.unlock(a1),
                bytes(0x8A, 0x01, 0x01, 0x11, 0x9B)));
    }

    @Test
    public void newerEndedAndStaleOperationsCannotBeReactivatedOrAffectCurrentState() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        assertTrue(policy.beginOperation(40L, LOCKER_DISCOVERY, null));

        assertFalse(policy.beginOperation(41L, LOCKER_CONFIRMED, null));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 40L, SerialWriteAttribution.discovery(1), queryOne()));
        assertTrue(policy.beginOperation(41L, LOCKER_DISCOVERY, null));
        assertEquals(INACTIVE_OPERATION, policy.authorize(
                LOCKER_DISCOVERY, 40L, SerialWriteAttribution.discovery(2), queryTwo()));

        policy.endOperation(40L);
        policy.endOperation(99L);
        policy.endOperation(0L);
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 41L, SerialWriteAttribution.discovery(1), queryOne()));

        policy.endOperation(41L);
        assertEquals(INACTIVE_OPERATION, policy.authorize(
                LOCKER_DISCOVERY, 41L, SerialWriteAttribution.discovery(2), queryTwo()));
        assertFalse(policy.beginOperation(41L, LOCKER_DISCOVERY, null));
        assertFalse(policy.beginOperation(40L, LOCKER_DISCOVERY, null));

        assertTrue(policy.beginOperation(42L, LOCKER_DISCOVERY, null));
        assertFalse(policy.beginOperation(42L, TERMINAL, null));
        assertFalse(policy.beginOperation(41L, TERMINAL, null));
        assertEquals(ALLOWED, policy.authorize(
                LOCKER_DISCOVERY, 42L, SerialWriteAttribution.discovery(1), queryOne()));

        assertTrue(policy.beginOperation(43L, TERMINAL, null));
        assertEquals(INACTIVE_OPERATION, policy.authorize(
                LOCKER_DISCOVERY, 42L, SerialWriteAttribution.discovery(2), queryTwo()));
    }

    @Test(timeout = 10_000L)
    public void concurrentDuplicateAttemptsConsumeExactlyOneDiscoveryQuota() throws Exception {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        assertTrue(policy.beginOperation(50L, LOCKER_DISCOVERY, null));
        int attempts = 24;
        ExecutorService workers = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CustomerSerialWritePolicy.Decision>> results = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return policy.authorize(
                            LOCKER_DISCOVERY,
                            50L,
                            SerialWriteAttribution.discovery(1),
                            queryOne());
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();

            int allowed = 0;
            int duplicate = 0;
            for (Future<CustomerSerialWritePolicy.Decision> result : results) {
                CustomerSerialWritePolicy.Decision decision = result.get(
                        5L, TimeUnit.SECONDS);
                if (decision == ALLOWED) {
                    allowed++;
                } else if (decision == DUPLICATE) {
                    duplicate++;
                }
            }
            assertEquals(1, allowed);
            assertEquals(attempts - 1, duplicate);
            assertEquals(ALLOWED, policy.authorize(
                    LOCKER_DISCOVERY,
                    50L,
                    SerialWriteAttribution.discovery(2),
                    queryTwo()));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void authorizedReturnBindsASeparateLocalOperationAndAllowsRepeatedExactStatusQueries() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        AuthorizedUnlockRequest request = b2Request(8_001L);

        assertTrue(policy.beginAuthorizedReturn(60L, request));
        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                60L,
                SerialWriteAttribution.doorStatus(b2(FeedbackPolarity.SHORT_WHEN_OPEN)),
                b2DoorQuery()));
        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                60L,
                SerialWriteAttribution.doorStatus(b2(FeedbackPolarity.SHORT_WHEN_OPEN)),
                b2DoorQuery()));
    }

    @Test
    public void legacyBeginCannotEnterSecureReturnPhasesOrConsumeTheOperationId() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();

        assertFalse(policy.beginOperation(61L, RETURN_DOOR_STATUS, null));
        assertFalse(policy.beginOperation(61L, RETURN_UNLOCK, null));
        assertFalse(policy.beginAuthorizedReturn(61L, null));
        assertFalse(policy.beginAuthorizedReturn(0L, b2Request(8_002L)));
        assertTrue(policy.beginAuthorizedReturn(61L, b2Request(8_003L)));
    }

    @Test
    public void authorizedReturnRejectsWrongStatusOriginTargetAndPayloadWithoutBlockingExactQuery() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget b2 = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        assertTrue(policy.beginAuthorizedReturn(62L, b2Request(8_004L)));

        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                RETURN_DOOR_STATUS,
                62L,
                SerialWriteAttribution.returnUnlock(b2),
                b2DoorQuery()));
        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                RETURN_DOOR_STATUS,
                62L,
                SerialWriteAttribution.doorStatus(
                        b2(FeedbackPolarity.SHORT_WHEN_LOCKED)),
                b2DoorQuery()));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                62L,
                SerialWriteAttribution.doorStatus(b2),
                bytes(0x80, 0x02, 0x03, 0x33, 0xB2)));

        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                62L,
                SerialWriteAttribution.doorStatus(b2),
                b2DoorQuery()));
    }

    @Test
    public void authorizedReturnEnforcesBaselineUnlockOnceAndPostUnlockStatusOnly() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget b2 = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        AuthorizedUnlockRequest request = b2Request(8_005L);
        assertTrue(policy.beginAuthorizedReturn(63L, request));

        assertFalse(policy.transitionAuthorizedReturn(63L, RETURN_UNLOCK));
        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                63L,
                SerialWriteAttribution.doorStatus(b2),
                b2DoorQuery()));
        assertFalse(policy.transitionAuthorizedReturn(63L, TERMINAL));
        assertTrue(policy.transitionAuthorizedReturn(63L, RETURN_UNLOCK));
        assertFalse(policy.transitionAuthorizedReturn(63L, RETURN_DOOR_STATUS));

        assertEquals(ATTRIBUTION_MISMATCH, policy.authorize(
                RETURN_UNLOCK,
                63L,
                SerialWriteAttribution.unlock(b2),
                b2Unlock()));
        assertEquals(PAYLOAD_NOT_ALLOWED, policy.authorize(
                RETURN_UNLOCK,
                63L,
                SerialWriteAttribution.returnUnlock(b2),
                bytes(0x8A, 0x02, 0x03, 0x11, 0x9A)));
        assertEquals(ALLOWED, policy.authorize(
                RETURN_UNLOCK,
                63L,
                SerialWriteAttribution.returnUnlock(b2),
                b2Unlock()));
        assertEquals(DUPLICATE, policy.authorize(
                RETURN_UNLOCK,
                63L,
                SerialWriteAttribution.returnUnlock(b2),
                b2Unlock()));
        assertTrue(policy.transitionAuthorizedReturn(63L, RETURN_DOOR_STATUS));

        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                63L,
                SerialWriteAttribution.doorStatus(b2),
                b2DoorQuery()));
        assertFalse(policy.transitionAuthorizedReturn(63L, RETURN_UNLOCK));
    }

    @Test
    public void endedOrReplacedReturnOperationsRejectStaleTransitionsAndWrites() {
        CustomerSerialWritePolicy policy = new CustomerSerialWritePolicy();
        LockerTarget b2 = b2(FeedbackPolarity.SHORT_WHEN_OPEN);
        assertTrue(policy.beginAuthorizedReturn(64L, b2Request(8_006L)));
        assertEquals(ALLOWED, policy.authorize(
                RETURN_DOOR_STATUS,
                64L,
                SerialWriteAttribution.doorStatus(b2),
                b2DoorQuery()));
        assertTrue(policy.beginOperation(65L, TERMINAL, null));

        assertFalse(policy.transitionAuthorizedReturn(64L, RETURN_UNLOCK));
        assertEquals(INACTIVE_OPERATION, policy.authorize(
                RETURN_DOOR_STATUS,
                64L,
                SerialWriteAttribution.doorStatus(b2),
                b2DoorQuery()));
        policy.endOperation(65L);
        assertFalse(policy.transitionAuthorizedReturn(65L, RETURN_UNLOCK));
        assertFalse(policy.beginAuthorizedReturn(65L, b2Request(8_007L)));
    }

    private static LockerTarget a1(FeedbackPolarity polarity) {
        return new LockerTarget(LockerZone.A, 1, 1, polarity);
    }

    private static LockerTarget b2(FeedbackPolarity polarity) {
        return new LockerTarget(LockerZone.B, 2, 2, polarity);
    }

    private static AuthorizedUnlockRequest b2Request(long serverOperationId) {
        return new AuthorizedUnlockRequest(
                serverOperationId,
                b2(FeedbackPolarity.SHORT_WHEN_OPEN),
                b2Unlock(),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B),
                bytes(0x8A, 0x02, 0x02, 0x00, 0x8A));
    }

    private static byte[] b2DoorQuery() {
        return bytes(0x80, 0x02, 0x02, 0x33, 0xB3);
    }

    private static byte[] b2Unlock() {
        return bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);
    }

    private static byte[] queryOne() {
        return bytes(0x80, 0x01, 0x01, 0x33, 0xB3);
    }

    private static byte[] queryTwo() {
        return bytes(0x80, 0x02, 0x01, 0x33, 0xB0);
    }

    private static byte[] queryThree() {
        return bytes(0x80, 0x03, 0x01, 0x33, 0xB1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
