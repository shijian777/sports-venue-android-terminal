package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class FailClosedReturnServiceClientTest {
    private static final String UNAVAILABLE = "服务器还柜协议未配置";

    @Test
    public void productionAssemblyNeverReturnsLocalFixturesOrAuthorization() {
        ReturnServiceClient client = ReturnServiceAssembly.create();
        ReturnIdentity identity = new ReturnIdentity(
                UnlockMethod.ID_CARD, "production-test-credential", 100L);
        ReturnLocker locker = new ReturnLocker("production-a1", "A1", "A区", new LockerTarget(
                LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED));
        ReturnAuthorization authorization = new ReturnAuthorization(9L, locker,
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x00, (byte) 0x8A},
                new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                "valid-production-test-token", 30_001L);

        ReturnServiceResult<ReturnLockerList> queried = client.queryActiveLockers(identity, 1L);
        ReturnServiceResult<ReturnAuthorization> authorized = client.authorize(identity, locker, 2L);
        ReturnServiceResult<ReturnCompletionReceipt> completed = client.complete(authorization);

        assertFalse(ReturnServiceAssembly.isLocalDemo());
        assertUnavailable(queried);
        assertUnavailable(authorized);
        assertUnavailable(completed);
    }

    @Test
    public void productionFailuresStayDeterministicForInvalidOrMissingInputs() {
        ReturnServiceClient client = ReturnServiceAssembly.create();

        assertUnavailable(client.queryActiveLockers(null, 0L));
        assertUnavailable(client.authorize(null, null, 0L));
        assertUnavailable(client.complete(null));
    }

    private static void assertUnavailable(ReturnServiceResult<?> result) {
        assertEquals(ReturnServiceResult.Code.PERMANENT_FAILURE, result.code());
        assertEquals(UNAVAILABLE, result.message());
        assertFalse(result.isSuccess());
        assertFalse(result.hasValue());
    }
}
