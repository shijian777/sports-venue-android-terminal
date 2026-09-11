package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReturnServiceClientTest {
    @Test
    public void typedFailureCannotBeMistakenForASuccessfulPayload() {
        ReturnServiceResult<ReturnAuthorization> result =
                ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);

        assertFalse(result.isSuccess());
        assertFalse(result.hasValue());
        assertTrue(result.code() == ReturnServiceResult.Code.REJECTED);
        assertNull(result.message());
    }

    @Test
    public void successfulPayloadKeepsTheLegacyNullMessageSemantics() {
        ReturnServiceResult<String> result = ReturnServiceResult.success("payload");

        assertTrue(result.isSuccess());
        assertEquals("payload", result.value());
        assertNull(result.message());
    }

    @Test
    public void typedFailureCanCarryAnExactNonemptyPresentationMessage() {
        ReturnServiceResult<ReturnAuthorization> result = ReturnServiceResult.failure(
                ReturnServiceResult.Code.PERMANENT_FAILURE,
                "服务器还柜协议未配置");

        assertEquals(ReturnServiceResult.Code.PERMANENT_FAILURE, result.code());
        assertEquals("服务器还柜协议未配置", result.message());
        assertFalse(result.isSuccess());
        assertFalse(result.hasValue());
        assertFailureMessageRejected(null);
        assertFailureMessageRejected("");
        assertFailureMessageRejected("   ");
    }

    @Test
    public void typedFailureRejectsUnicodeBlankMessagesWithoutNormalizingText() {
        assertFailureMessageRejected(" \t\r\n\f");
        assertFailureMessageRejected("\u3000");
        assertFailureMessageRejected("\u00a0");
        assertFailureMessageRejected("\u2003");

        String original = "\u2003服务器还柜协议未配置\u00a0";
        ReturnServiceResult<ReturnAuthorization> result = ReturnServiceResult.failure(
                ReturnServiceResult.Code.PERMANENT_FAILURE, original);

        assertEquals(original, result.message());
    }

    @Test
    public void queriedLockerPayloadOwnsAnImmutableSnapshot() {
        ArrayList<ReturnLocker> callerList = new ArrayList<>();
        ReturnLocker locker = new ReturnLocker("server-1", "A-01", "Lobby", new LockerTarget(
                LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED));
        callerList.add(locker);
        ReturnLockerList payload = ReturnLockerList.of(callerList);
        callerList.clear();

        assertTrue(payload.lockers().equals(Collections.singletonList(locker)));
        try {
            payload.lockers().clear();
            throw new AssertionError("Payload list must not be mutable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(payload.lockers().equals(Collections.singletonList(locker)));
        }
    }

    @Test
    public void serviceValueConstructorsRejectAmbiguousOrMalformedValues() {
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnServiceResult.success(null);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnServiceResult.failure(ReturnServiceResult.Code.SUCCESS);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnServiceResult.failure(
                        ReturnServiceResult.Code.SUCCESS, "not-a-failure");
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnCompletionReceipt(0L, false);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnLockerList.of(null);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnLockerList.of(Arrays.<ReturnLocker>asList((ReturnLocker) null));
            }
        });
    }

    @Test
    public void queriedLockerListRejectsDuplicateServerIds() {
        final ReturnLocker first = locker("duplicate", 1);
        final ReturnLocker second = locker("duplicate", 2);
        assertInvalid(new Runnable() {
            @Override public void run() {
                ReturnLockerList.of(Arrays.asList(first, second));
            }
        });
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid value to be rejected");
    }

    private static void assertFailureMessageRejected(String message) {
        try {
            ReturnServiceResult.failure(ReturnServiceResult.Code.PERMANENT_FAILURE, message);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Failure message must be nonempty");
    }

    private static ReturnLocker locker(String id, int localLock) {
        return new ReturnLocker(id, "A-0" + localLock, "Lobby", new LockerTarget(
                LockerZone.A, 1, localLock, FeedbackPolarity.SHORT_WHEN_LOCKED));
    }
}
