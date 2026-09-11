package com.codex.lockertest.returnflow;

import com.codex.lockertest.runtime.DemoCredentials;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalDemoReturnServiceClientTest {
    @Test
    public void idCardFixtureReturnsOnlyLiteralA1LockerMetadata() {
        ReturnServiceClient client = ReturnServiceAssembly.create();

        ReturnServiceResult<ReturnLockerList> result = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L), 1L);

        assertTrue(ReturnServiceAssembly.isLocalDemo());
        assertTrue(result.isSuccess());
        assertEquals(1, result.value().lockers().size());
        ReturnLocker locker = result.value().lockers().get(0);
        assertEquals("local-demo-a1", locker.serverLockerId());
        assertEquals("A1", locker.displayLabel());
        assertEquals("A区", locker.areaDisplayName());
        assertEquals(LockerZone.A, locker.target().zone());
        assertEquals(1, locker.target().boardAddress());
        assertEquals(1, locker.target().localLock());
        assertEquals(FeedbackPolarity.SHORT_WHEN_LOCKED, locker.target().feedbackPolarity());
    }

    @Test
    public void qrFixtureReturnsLiteralA1ThenA2InOrder() {
        ReturnServiceResult<ReturnLockerList> result = ReturnServiceAssembly.create().queryActiveLockers(
                new ReturnIdentity(UnlockMethod.QR, DemoCredentials.QR_CODE, 100L), 2L);

        assertTrue(result.isSuccess());
        assertEquals(2, result.value().lockers().size());
        assertEquals("local-demo-a1", result.value().lockers().get(0).serverLockerId());
        assertEquals("A1", result.value().lockers().get(0).displayLabel());
        assertEquals("local-demo-a2", result.value().lockers().get(1).serverLockerId());
        assertEquals("A2", result.value().lockers().get(1).displayLabel());
        assertEquals(2, result.value().lockers().get(1).target().localLock());
    }

    @Test
    public void unknownAndMethodMismatchedIdentitiesReceiveImmutableEmptyLists() {
        ReturnServiceClient client = ReturnServiceAssembly.create();
        ReturnServiceResult<ReturnLockerList> unknown = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.ID_CARD, "unknown-card", 100L), 3L);
        ReturnServiceResult<ReturnLockerList> mismatched = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.PHONE, DemoCredentials.ID_CARD, 100L), 4L);

        assertTrue(unknown.isSuccess());
        assertTrue(mismatched.isSuccess());
        assertTrue(unknown.value().isEmpty());
        assertTrue(mismatched.value().isEmpty());
        try {
            unknown.value().lockers().add(null);
            throw new AssertionError("Unknown identity list must stay immutable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(unknown.value().isEmpty());
        }
    }

    @Test
    public void opaqueFaceAndPalmCredentialsMapToA1WithoutClaimingVerification() {
        ReturnServiceClient client = ReturnServiceAssembly.create();
        ReturnServiceResult<ReturnLockerList> face = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.FACE, "verified-opaque-face", 100L), 5L);
        ReturnServiceResult<ReturnLockerList> palm = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.PALM, "verified-opaque-palm", 100L), 6L);

        assertTrue(face.isSuccess());
        assertTrue(palm.isSuccess());
        assertEquals("local-demo-a1", face.value().lockers().get(0).serverLockerId());
        assertEquals("local-demo-a1", palm.value().lockers().get(0).serverLockerId());
        assertFalse(face.value().isEmpty());
        assertFalse(palm.value().isEmpty());
    }

    @Test
    public void exactPhoneAndPasswordFixturesEachMapToA1() {
        ReturnServiceClient client = ReturnServiceAssembly.create();

        ReturnServiceResult<ReturnLockerList> phone = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.PHONE, DemoCredentials.PHONE, 100L), 7L);
        ReturnServiceResult<ReturnLockerList> password = client.queryActiveLockers(
                new ReturnIdentity(UnlockMethod.PASSWORD, DemoCredentials.PASSWORD, 100L), 8L);

        assertTrue(phone.isSuccess());
        assertTrue(password.isSuccess());
        assertEquals("local-demo-a1", phone.value().lockers().get(0).serverLockerId());
        assertEquals("local-demo-a1", password.value().lockers().get(0).serverLockerId());
    }

    @Test
    public void authorizationForOwnedA1UsesLiteralFrames() {
        ReturnServiceClient client = ReturnServiceAssembly.create();
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnLocker a1 = client.queryActiveLockers(identity, 9L).value().lockers().get(0);

        ReturnServiceResult<ReturnAuthorization> result = client.authorize(identity, a1, 91L);

        assertTrue(result.isSuccess());
        assertEquals(91L, result.value().operationId());
        assertArrayEquals(new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                result.value().unlockCommand());
        assertArrayEquals(new byte[] {(byte) 0x8A, 0x01, 0x01, 0x00, (byte) 0x8A},
                result.value().expectedSuccessFrame());
        assertArrayEquals(new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                result.value().expectedFailureFrame());
    }

    @Test
    public void authorizationRejectsDuplicateOperationUnknownLockerAndAnotherIdentityLocker() {
        LocalDemoReturnServiceClient client = clientAt(1_000L);
        ReturnIdentity id = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnIdentity qr = new ReturnIdentity(UnlockMethod.QR, DemoCredentials.QR_CODE, 100L);
        ReturnLocker a1 = client.queryActiveLockers(id, 10L).value().lockers().get(0);
        ReturnLocker a2 = client.queryActiveLockers(qr, 11L).value().lockers().get(1);
        ReturnLocker unknown = new ReturnLocker("local-demo-a3", "A3", "A区", new LockerTarget(
                LockerZone.A, 1, 3, FeedbackPolarity.SHORT_WHEN_LOCKED));

        assertTrue(client.authorize(id, a1, 92L).isSuccess());
        assertTrue(client.authorize(id, a1, 92L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.authorize(id, a2, 93L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.authorize(id, unknown, 94L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.queryActiveLockers(null, 95L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.authorize(null, a1, 96L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.complete(null).code() == ReturnServiceResult.Code.REJECTED);
    }

    @Test
    public void completionRemovesOnlyQrsA1ThenAllowsA2AndIsIdempotent() {
        LocalDemoReturnServiceClient client = clientAt(2_000L);
        ReturnIdentity qr = new ReturnIdentity(UnlockMethod.QR, DemoCredentials.QR_CODE, 100L);
        ReturnIdentity id = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnLocker a1 = client.queryActiveLockers(qr, 12L).value().lockers().get(0);
        ReturnAuthorization a1Authorization = client.authorize(qr, a1, 101L).value();

        ReturnServiceResult<ReturnCompletionReceipt> first = client.complete(a1Authorization);
        assertTrue(first.isSuccess());
        assertFalse(first.value().alreadyCompleted());
        assertEquals("local-demo-a2", client.queryActiveLockers(qr, 13L).value().lockers().get(0).serverLockerId());
        assertEquals("local-demo-a1", client.queryActiveLockers(id, 14L).value().lockers().get(0).serverLockerId());
        assertTrue(client.complete(a1Authorization).value().alreadyCompleted());

        ReturnLocker a2 = client.queryActiveLockers(qr, 15L).value().lockers().get(0);
        ReturnAuthorization a2Authorization = client.authorize(qr, a2, 102L).value();
        assertArrayEquals(new byte[] {(byte) 0x8A, 0x01, 0x02, 0x11, (byte) 0x98},
                a2Authorization.unlockCommand());
        assertArrayEquals(new byte[] {(byte) 0x8A, 0x01, 0x02, 0x00, (byte) 0x89},
                a2Authorization.expectedSuccessFrame());
        assertTrue(client.complete(a2Authorization).isSuccess());
        assertTrue(client.queryActiveLockers(qr, 16L).value().isEmpty());
    }

    @Test
    public void issuedAuthorizationCompletesAfterOpenDeadlineAndRemainsIdempotent() {
        final long[] now = new long[] {3_000L};
        LocalDemoReturnServiceClient client = new LocalDemoReturnServiceClient(
                new java.util.function.LongSupplier() {
                    @Override public long getAsLong() {
                        return now[0];
                    }
                }, fixedTokens());
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnLocker a1 = client.queryActiveLockers(identity, 17L).value().lockers().get(0);
        ReturnAuthorization issued = client.authorize(identity, a1, 103L).value();
        assertEquals(33_000L, issued.expiresAt());
        ReturnAuthorization forged = new ReturnAuthorization(issued.operationId(), issued.locker(),
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x00, (byte) 0x8A},
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                issued.completionToken(), issued.expiresAt());
        assertTrue(client.complete(forged).code() == ReturnServiceResult.Code.REJECTED);
        now[0] = 35_000L;
        ReturnServiceResult<ReturnCompletionReceipt> first = client.complete(issued);
        assertTrue(first.isSuccess());
        assertFalse(first.value().alreadyCompleted());
        assertTrue(client.queryActiveLockers(identity, 18L).value().isEmpty());
        now[0] = 95_000L;
        ReturnServiceResult<ReturnCompletionReceipt> replay = client.complete(issued);
        assertTrue(replay.isSuccess());
        assertTrue(replay.value().alreadyCompleted());
    }

    @Test
    public void queryRejectsNonpositiveRequestIdsWithTypedFailures() {
        LocalDemoReturnServiceClient client = clientAt(4_000L);
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);

        assertTrue(client.queryActiveLockers(identity, 0L).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.queryActiveLockers(identity, -1L).code() == ReturnServiceResult.Code.REJECTED);
    }

    @Test
    public void completionRejectsAuthorizationIssuedByAnotherClient() {
        LocalDemoReturnServiceClient issuingClient = clientAt(5_000L);
        LocalDemoReturnServiceClient otherClient = clientAt(5_000L);
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnLocker a1 = issuingClient.queryActiveLockers(identity, 19L).value().lockers().get(0);
        ReturnAuthorization issued = issuingClient.authorize(identity, a1, 104L).value();

        assertTrue(otherClient.complete(issued).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(issuingClient.complete(issued).isSuccess());
    }

    @Test
    public void completionRejectsClonedAndAlteredAuthorizations() {
        LocalDemoReturnServiceClient client = clientAt(6_000L);
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        ReturnLocker a1 = client.queryActiveLockers(identity, 20L).value().lockers().get(0);
        ReturnAuthorization issued = client.authorize(identity, a1, 105L).value();
        ReturnAuthorization clone = authorization(issued, issued.completionToken());
        ReturnAuthorization alteredToken = authorization(issued, "altered-token");

        assertTrue(client.complete(clone).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.complete(alteredToken).code() == ReturnServiceResult.Code.REJECTED);
        assertTrue(client.complete(issued).isSuccess());
    }

    @Test
    public void concurrentUniqueOperationsEachReceiveTheirOwnAuthorization() throws Exception {
        final LocalDemoReturnServiceClient client = clientAt(7_000L);
        final ReturnIdentity identity = new ReturnIdentity(
                UnlockMethod.ID_CARD, DemoCredentials.ID_CARD, 100L);
        final ReturnLocker a1 = client.queryActiveLockers(identity, 21L).value().lockers().get(0);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<ReturnServiceResult<ReturnAuthorization>>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                final long operationId = 200L + index;
                futures.add(workers.submit(new Callable<ReturnServiceResult<ReturnAuthorization>>() {
                    @Override public ReturnServiceResult<ReturnAuthorization> call() throws Exception {
                        start.await(5L, TimeUnit.SECONDS);
                        return client.authorize(identity, a1, operationId);
                    }
                }));
            }
            start.countDown();
            for (int index = 0; index < futures.size(); index++) {
                ReturnServiceResult<ReturnAuthorization> result = futures.get(index).get(5L, TimeUnit.SECONDS);
                assertTrue(result.isSuccess());
                assertEquals(200L + index, result.value().operationId());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static LocalDemoReturnServiceClient clientAt(final long now) {
        return new LocalDemoReturnServiceClient(new java.util.function.LongSupplier() {
            @Override public long getAsLong() {
                return now;
            }
        }, fixedTokens());
    }

    private static LocalDemoReturnServiceClient.TokenSource fixedTokens() {
        return new LocalDemoReturnServiceClient.TokenSource() {
            private int count;

            @Override public String nextToken() {
                count++;
                return "test-token-" + count;
            }
        };
    }

    private static ReturnAuthorization authorization(ReturnAuthorization source, String token) {
        return new ReturnAuthorization(source.operationId(), source.locker(),
                source.unlockCommand(), source.expectedSuccessFrame(), source.expectedFailureFrame(), token,
                source.expiresAt());
    }
}
