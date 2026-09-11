package com.codex.lockertest.unlock;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.returnflow.ReturnAuthorization;
import com.codex.lockertest.returnflow.ReturnLocker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AuthorizedUnlockRequestTest {
    private static final byte[] COMMAND = bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);
    private static final byte[] SUCCESS = bytes(0x8A, 0x02, 0x02, 0x00, 0x8A);
    private static final byte[] FAILURE = bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);

    @Test
    public void requestKeepsExactFramesInDefensiveSnapshots() {
        byte[] command = COMMAND.clone();
        byte[] success = SUCCESS.clone();
        byte[] failure = FAILURE.clone();
        AuthorizedUnlockRequest request = new AuthorizedUnlockRequest(
                71L, b2(), command, success, failure);

        command[0] = 0;
        success[0] = 0;
        failure[0] = 0;
        byte[] returnedCommand = request.unlockCommand();
        byte[] returnedSuccess = request.expectedSuccessFrame();
        byte[] returnedFailure = request.expectedFailureFrame();
        returnedCommand[1] = 0;
        returnedSuccess[1] = 0;
        returnedFailure[1] = 0;

        assertEquals(71L, request.operationId());
        assertEquals(2, request.target().boardAddress());
        assertEquals(2, request.target().localLock());
        assertArrayEquals(COMMAND, request.unlockCommand());
        assertArrayEquals(SUCCESS, request.expectedSuccessFrame());
        assertArrayEquals(FAILURE, request.expectedFailureFrame());
    }

    @Test
    public void requestRejectsMissingMalformedOrOtherLockerFrames() {
        assertInvalid(() -> new AuthorizedUnlockRequest(0L, b2(), COMMAND, SUCCESS, FAILURE));
        assertInvalid(() -> new AuthorizedUnlockRequest(1L, null, COMMAND, SUCCESS, FAILURE));
        assertInvalid(() -> new AuthorizedUnlockRequest(
                1L, b2(), bytes(0x8A), SUCCESS, FAILURE));
        assertInvalid(() -> new AuthorizedUnlockRequest(
                1L, b2(), bytes(0x8A, 0x02, 0x03, 0x11, 0x9A), SUCCESS, FAILURE));
        assertInvalid(() -> new AuthorizedUnlockRequest(
                1L, b2(), COMMAND, bytes(0x8A, 0x02, 0x02, 0x00, 0x00), FAILURE));
        assertInvalid(() -> new AuthorizedUnlockRequest(
                1L, b2(), COMMAND, FAILURE, SUCCESS));
    }

    @Test
    public void factoryCopiesOnlyTheSerialAuthorityAndNeverRetainsTheCompletionToken() {
        ReturnAuthorization authorization = new ReturnAuthorization(
                72L,
                new ReturnLocker("server-b2", "B2", "B区", b2()),
                COMMAND,
                SUCCESS,
                FAILURE,
                "private-completion-token",
                9_999L);

        AuthorizedUnlockRequest request = AuthorizedUnlockRequest.from(authorization);

        assertEquals(72L, request.operationId());
        assertArrayEquals(COMMAND, request.unlockCommand());
        assertFalse(request.toString().contains("private-completion-token"));
        assertTrue(Modifier.isFinal(AuthorizedUnlockRequest.class.getModifiers()));
        for (Field field : AuthorizedUnlockRequest.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(field.getName(), Modifier.isPrivate(field.getModifiers()));
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
            assertFalse(field.getName(), field.getType() == String.class);
            assertFalse(field.getName(), field.getType() == ReturnAuthorization.class);
        }
    }

    private static LockerTarget b2() {
        return new LockerTarget(
                LockerZone.B, 2, 2, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected request to be rejected");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
